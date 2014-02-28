/**
 * This file is part of Waarp Project.
 * 
 * Copyright 2009, Frederic Bregier, and individual contributors by the @author tags. See the
 * COPYRIGHT.txt in the distribution for a full listing of individual contributors.
 * 
 * All Waarp Project is free software: you can redistribute it and/or modify it under the terms of
 * the GNU General Public License as published by the Free Software Foundation, either version 3 of
 * the License, or (at your option) any later version.
 * 
 * Waarp is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without even
 * the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU General
 * Public License for more details.
 * 
 * You should have received a copy of the GNU General Public License along with Waarp . If not, see
 * <http://www.gnu.org/licenses/>.
 */
package org.waarp.openr66.proxy.network;

import java.net.ConnectException;
import java.net.SocketAddress;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.jboss.netty.bootstrap.ClientBootstrap;
import org.jboss.netty.channel.Channel;
import org.jboss.netty.channel.ChannelFactory;
import org.jboss.netty.channel.ChannelFuture;
import org.jboss.netty.channel.ChannelPipelineException;
import org.jboss.netty.channel.Channels;
import org.jboss.netty.channel.group.ChannelGroup;
import org.jboss.netty.channel.group.DefaultChannelGroup;
import org.jboss.netty.channel.socket.nio.NioClientSocketChannelFactory;
import org.waarp.common.crypto.ssl.WaarpSslUtility;
import org.waarp.common.logging.WaarpInternalLogger;
import org.waarp.common.logging.WaarpInternalLoggerFactory;
import org.waarp.common.utility.WaarpThreadFactory;
import org.waarp.openr66.protocol.exception.OpenR66Exception;
import org.waarp.openr66.protocol.exception.OpenR66ProtocolNetworkException;
import org.waarp.openr66.protocol.exception.OpenR66ProtocolNoConnectionException;
import org.waarp.openr66.protocol.exception.OpenR66ProtocolRemoteShutdownException;
import org.waarp.openr66.protocol.utils.ChannelUtils;
import org.waarp.openr66.protocol.utils.R66ShutdownHook;
import org.waarp.openr66.proxy.configuration.Configuration;
import org.waarp.openr66.proxy.network.ssl.NetworkSslServerHandler;
import org.waarp.openr66.proxy.network.ssl.NetworkSslServerPipelineFactory;

/**
 * This class handles Network Transaction connections
 * 
 * @author frederic bregier
 */
public class NetworkTransaction {
	/**
	 * Internal Logger
	 */
	private static final WaarpInternalLogger logger = WaarpInternalLoggerFactory
			.getLogger(NetworkTransaction.class);

	/**
	 * ExecutorService Server Boss
	 */
	private final ExecutorService execServerBoss = Executors
			.newCachedThreadPool(new WaarpThreadFactory("ServerBossProxy"));
	/**
	 * ExecutorService Server Worker
	 */
	private final ExecutorService execServerWorker = Executors
			.newCachedThreadPool(new WaarpThreadFactory("ServerWorkerProxy"));

	private final ChannelFactory channelClientFactory = new NioClientSocketChannelFactory(
			execServerBoss,
			execServerWorker,
			Configuration.configuration.CLIENT_THREAD);

	private final ClientBootstrap clientBootstrap = new ClientBootstrap(
			channelClientFactory);
	private final ClientBootstrap clientSslBootstrap = new ClientBootstrap(
			channelClientFactory);
	private final ChannelGroup networkChannelGroup = new DefaultChannelGroup(
			"NetworkChannels");
	private final NetworkServerPipelineFactory networkServerPipelineFactory;
	private final NetworkSslServerPipelineFactory networkSslServerPipelineFactory;

	public NetworkTransaction() {
		networkServerPipelineFactory = new NetworkServerPipelineFactory(false);
		clientBootstrap.setPipelineFactory(networkServerPipelineFactory);
		clientBootstrap.setOption("tcpNoDelay", true);
		clientBootstrap.setOption("reuseAddress", true);
		clientBootstrap.setOption("connectTimeoutMillis",
				Configuration.configuration.TIMEOUTCON);
		if (Configuration.configuration.useSSL && Configuration.configuration.HOST_SSLID != null) {
			networkSslServerPipelineFactory =
					new NetworkSslServerPipelineFactory(true);
			clientSslBootstrap.setPipelineFactory(networkSslServerPipelineFactory);
			clientSslBootstrap.setOption("tcpNoDelay", true);
			clientSslBootstrap.setOption("reuseAddress", true);
			clientSslBootstrap.setOption("connectTimeoutMillis",
					Configuration.configuration.TIMEOUTCON);
		} else {
			networkSslServerPipelineFactory = null;
			logger.warn("No SSL support configured");
		}
	}

	/**
	 * Create a connection to the specified socketAddress with multiple retries
	 * 
	 * @param socketAddress
	 * @param isSSL
	 * @return the Channel
	 */
	public Channel createConnectionWithRetry(SocketAddress socketAddress,
			boolean isSSL) {
		Channel channel = null;
		OpenR66Exception lastException = null;
		for (int i = 0; i < Configuration.RETRYNB; i++) {
			try {
				channel = createConnection(socketAddress, isSSL);
				break;
			} catch (OpenR66ProtocolRemoteShutdownException e1) {
				lastException = e1;
				channel = null;
				break;
			} catch (OpenR66ProtocolNoConnectionException e1) {
				lastException = e1;
				channel = null;
				break;
			} catch (OpenR66ProtocolNetworkException e1) {
				// Can retry
				lastException = e1;
				channel = null;
				try {
					Thread.sleep(Configuration.WAITFORNETOP);
				} catch (InterruptedException e) {
					break;
				}
			}
		}
		if (channel == null) {
			logger.debug("Cannot connect : {}", lastException.getMessage());
		} else if (lastException != null) {
			logger.debug("Connection retried since {}", lastException.getMessage());
		}
		return channel;
	}

	/**
	 * Create a connection to the specified socketAddress
	 * 
	 * @param socketAddress
	 * @param isSSL
	 * @return the channel
	 * @throws OpenR66ProtocolNetworkException
	 * @throws OpenR66ProtocolRemoteShutdownException
	 * @throws OpenR66ProtocolNoConnectionException
	 */
	private Channel createConnection(SocketAddress socketAddress, boolean isSSL)
			throws OpenR66ProtocolNetworkException,
			OpenR66ProtocolRemoteShutdownException,
			OpenR66ProtocolNoConnectionException {
		Channel channel = null;
		boolean ok = false;
		// check valid limit on server side only (could be the initiator but not a client)
		boolean valid = false;
		for (int i = 0; i < Configuration.RETRYNB * 2; i++) {
			if (Configuration.configuration.constraintLimitHandler.checkConstraintsSleep(i)) {
				logger.debug("Constraints exceeded: " + i);
			} else {
				logger.debug("Constraints NOT exceeded");
				valid = true;
				break;
			}
		}
		if (!valid) {
			// Limit is locally exceeded
			logger.debug("Overloaded local system");
			throw new OpenR66ProtocolNetworkException(
					"Cannot connect to remote server due to local overload");
		}
		try {
			channel = createNewConnection(socketAddress, isSSL);
			ok = true;
		} finally {
			if (!ok) {
				if (channel != null) {
					if (channel.isOpen()) {
						WaarpSslUtility.closingSslChannel(channel);
					}
					channel = null;
				}
			}
		}
		return channel;
	}

	/**
	 * 
	 * @param socketServerAddress
	 * @param isSSL
	 * @return the channel
	 * @throws OpenR66ProtocolNetworkException
	 * @throws OpenR66ProtocolRemoteShutdownException
	 * @throws OpenR66ProtocolNoConnectionException
	 */
	private Channel createNewConnection(SocketAddress socketServerAddress, boolean isSSL)
			throws OpenR66ProtocolNetworkException,
			OpenR66ProtocolRemoteShutdownException,
			OpenR66ProtocolNoConnectionException {
		ChannelFuture channelFuture = null;
		for (int i = 0; i < Configuration.RETRYNB; i++) {
			try {
				if (isSSL) {
					if (Configuration.configuration.HOST_SSLID != null) {
						channelFuture = clientSslBootstrap.connect(socketServerAddress);
					} else {
						throw new OpenR66ProtocolNoConnectionException("No SSL support");
					}
				} else {
					channelFuture = clientBootstrap.connect(socketServerAddress);
				}
			} catch (ChannelPipelineException e) {
				throw new OpenR66ProtocolNoConnectionException(
						"Cannot connect to remote server due to a channel exception");
			}
			try {
				channelFuture.await();
			} catch (InterruptedException e1) {
			}
			if (channelFuture.isSuccess()) {
				final Channel channel = channelFuture.getChannel();
				if (isSSL) {
					if (!NetworkSslServerHandler.isSslConnectedChannel(channel)) {
						logger.debug("KO CONNECT since SSL handshake is over");
						Channels.close(channel);
						throw new OpenR66ProtocolNoConnectionException(
								"Cannot finish connect to remote server");
					}
				}
				networkChannelGroup.add(channel);
				return channel;
			} else {
				try {
					Thread.sleep(Configuration.WAITFORNETOP);
				} catch (InterruptedException e) {
				}
				if (!channelFuture.isDone()) {
					throw new OpenR66ProtocolNoConnectionException(
							"Cannot connect to remote server due to interruption");
				}
				if (channelFuture.getCause() instanceof ConnectException) {
					logger.debug("KO CONNECT:" +
							channelFuture.getCause().getMessage());
					throw new OpenR66ProtocolNoConnectionException(
							"Cannot connect to remote server", channelFuture
									.getCause());
				} else {
					logger.debug("KO CONNECT but retry", channelFuture
							.getCause());
				}
			}
		}
		throw new OpenR66ProtocolNetworkException(
				"Cannot connect to remote server", channelFuture.getCause());
	}

	/**
	 * Close all Network Ttransaction
	 */
	public void closeAll() {
		logger.debug("close All Network Channels");
		try {
			Thread.sleep(Configuration.RETRYINMS * 2);
		} catch (InterruptedException e) {
		}
		if (!Configuration.configuration.isServer) {
			R66ShutdownHook.shutdownHook.launchFinalExit();
		}
		for (Channel channel : networkChannelGroup) {
			WaarpSslUtility.closingSslChannel(channel);
		}
		networkChannelGroup.close().awaitUninterruptibly();
		clientBootstrap.releaseExternalResources();
		clientSslBootstrap.releaseExternalResources();
		channelClientFactory.releaseExternalResources();
		try {
			Thread.sleep(Configuration.WAITFORNETOP);
		} catch (InterruptedException e) {
		}
		Configuration.configuration.clientStop();
		logger.debug("Last action before exit");
		ChannelUtils.stopLogger();
	}

	/**
	 * @return The number of Network Channels
	 */
	public int getNumberClients() {
		return networkChannelGroup.size();
	}
}
