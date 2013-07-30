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

import java.net.BindException;
import java.net.SocketAddress;

import org.jboss.netty.channel.Channel;
import org.jboss.netty.channel.ChannelHandlerContext;
import org.jboss.netty.channel.ChannelStateEvent;
import org.jboss.netty.channel.Channels;
import org.jboss.netty.channel.ExceptionEvent;
import org.jboss.netty.channel.MessageEvent;
import org.jboss.netty.handler.timeout.IdleStateAwareChannelHandler;
import org.jboss.netty.handler.timeout.IdleStateEvent;
import org.jboss.netty.handler.timeout.ReadTimeoutException;
import org.waarp.common.crypto.ssl.WaarpSslUtility;
import org.waarp.common.logging.WaarpInternalLogger;
import org.waarp.common.logging.WaarpInternalLoggerFactory;
import org.waarp.openr66.protocol.exception.OpenR66Exception;
import org.waarp.openr66.protocol.exception.OpenR66ExceptionTrappedFactory;
import org.waarp.openr66.protocol.exception.OpenR66ProtocolBusinessNoWriteBackException;
import org.waarp.openr66.protocol.exception.OpenR66ProtocolNetworkException;
import org.waarp.openr66.protocol.exception.OpenR66ProtocolNoConnectionException;
import org.waarp.openr66.protocol.exception.OpenR66ProtocolPacketException;
import org.waarp.openr66.protocol.localhandler.packet.AbstractLocalPacket;
import org.waarp.openr66.protocol.localhandler.packet.ConnectionErrorPacket;
import org.waarp.openr66.protocol.localhandler.packet.KeepAlivePacket;
import org.waarp.openr66.protocol.localhandler.packet.LocalPacketCodec;
import org.waarp.openr66.protocol.localhandler.packet.LocalPacketFactory;
import org.waarp.openr66.protocol.networkhandler.packet.NetworkPacket;
import org.waarp.openr66.protocol.utils.ChannelCloseTimer;
import org.waarp.openr66.protocol.utils.ChannelUtils;
import org.waarp.openr66.protocol.utils.R66Future;
import org.waarp.openr66.proxy.configuration.Configuration;

/**
 * Network Server Handler (Requester side)
 * 
 * @author frederic bregier
 */
public class NetworkServerHandler extends IdleStateAwareChannelHandler {
	// extends SimpleChannelHandler {
	/**
	 * Internal Logger
	 */
	private static final WaarpInternalLogger logger = WaarpInternalLoggerFactory
			.getLogger(NetworkServerHandler.class);

	/**
	 * The underlying Network Channel
	 */
	private volatile Channel networkChannel;
	/**
	 * The underlying Proxified associated Channel
	 */
	private volatile Channel proxyChannel;
	/**
	 * The associated bridge
	 */
	private volatile ProxyBridge bridge;
	/**
	 * The associated Local Address
	 */
	private volatile SocketAddress localAddress;
	/**
	 * Does this Handler is for SSL
	 */
	protected volatile boolean isSSL = false;
	/**
	 * Is this Handler a server side
	 */
	protected boolean isServer = false;
	/**
	 * To handle the keep alive
	 */
	protected volatile boolean keepAlivedSent = false;
	/**
	 * Future to wait for Client to be setup
	 */
	protected volatile R66Future clientFuture;

	/**
	 * 
	 * @param isServer
	 */
	public NetworkServerHandler(boolean isServer) {
		this.isServer = isServer;
		if (!this.isServer) {
			clientFuture = new R66Future(true);
		}
	}

	public void setBridge(ProxyBridge bridge) {
		this.bridge = bridge;
		this.proxyChannel = bridge.getSource().getNetworkChannel();
		this.clientFuture.setSuccess();
		logger.debug("setBridge: " + isServer + " "
				+ (bridge != null ? bridge.getProxyEntry().toString()
						+ " proxyChannelId: " + this.proxyChannel.getId() : "nobridge"));
	}

	/**
	 * @return the networkChannel
	 */
	public Channel getNetworkChannel() {
		return networkChannel;
	}

	public void close() {
		WaarpSslUtility.closingSslChannel(networkChannel);
	}

	@Override
	public void channelClosed(ChannelHandlerContext ctx, ChannelStateEvent e) {
		if (proxyChannel != null) {
			WaarpSslUtility.closingSslChannel(proxyChannel);
		}
	}

	@Override
	public void channelConnected(ChannelHandlerContext ctx, ChannelStateEvent e)
			throws OpenR66ProtocolNetworkException {
		this.networkChannel = e.getChannel();
		this.localAddress = this.networkChannel.getLocalAddress();
		if (isServer) {
			ProxyEntry entry = ProxyEntry.get(localAddress.toString());
			if (entry == null) {
				// error
				// XXX FIXME need to send error !
				WaarpSslUtility.closingSslChannel(networkChannel);
				logger.error("No proxy configuration found for: " + localAddress.toString());
				return;
			}
			this.bridge = new ProxyBridge(entry, this);
			this.bridge.initializeProxy();
			if (!this.bridge.waitForRemoteConnection()) {
				logger.error("No connection for proxy: " + localAddress.toString());
				WaarpSslUtility.closingSslChannel(this.networkChannel);
				return;
			}
			this.proxyChannel = this.bridge.getProxified().networkChannel;
			logger.warn("Connected: " + isServer + " "
					+ (bridge != null ? bridge.getProxyEntry().toString()
							+ " proxyChannelId: " + this.proxyChannel.getId() : "nobridge"));
		} else {
			try {
				this.clientFuture.await(Configuration.configuration.TIMEOUTCON);
			} catch (InterruptedException e1) {
			}
			if (this.bridge == null) {
				logger.error("No connection for proxy: " + localAddress.toString());
				WaarpSslUtility.closingSslChannel(this.networkChannel);
				return;
			}
			this.bridge.remoteConnected();
		}
		logger.debug("Network Channel Connected: {} ", e.getChannel().getId());
	}

	@Override
	public void channelIdle(ChannelHandlerContext ctx, IdleStateEvent e)
			throws Exception {
		if (Configuration.configuration.isShutdown)
			return;
		if (keepAlivedSent) {
			logger.error("Not getting KAlive: closing channel");
			if (Configuration.configuration.r66Mib != null) {
				Configuration.configuration.r66Mib.notifyWarning(
						"KeepAlive get no answer", "Closing network connection");
			}
			ChannelCloseTimer.closeFutureChannel(e.getChannel());
		} else {
			keepAlivedSent = true;
			KeepAlivePacket keepAlivePacket = new KeepAlivePacket();
			NetworkPacket response =
					new NetworkPacket(ChannelUtils.NOCHANNEL,
							ChannelUtils.NOCHANNEL, keepAlivePacket, null);
			logger.info("Write KAlive");
			Channels.write(e.getChannel(), response);
		}
	}

	public void setKeepAlivedSent() {
		keepAlivedSent = false;
	}

	@Override
	public void messageReceived(ChannelHandlerContext ctx, MessageEvent e) {
		final NetworkPacket packet = (NetworkPacket) e.getMessage();
		if (packet.getCode() == LocalPacketFactory.NOOPPACKET) {
			// Do nothing
			return;
		} else if (packet.getCode() == LocalPacketFactory.CONNECTERRORPACKET) {
			logger.debug("NetworkRecv: {}", packet);
			// Special code to STOP here
			if (packet.getLocalId() == ChannelUtils.NOCHANNEL) {
				// No way to know what is wrong: close all connections with
				// remote host
				logger.error("Will close NETWORK channel, Cannot continue connection with remote Host: "
						+
						packet.toString() +
						" : " +
						e.getChannel().getRemoteAddress());
				WaarpSslUtility.closingSslChannel(e.getChannel());
				return;
			}
		} else if (packet.getCode() == LocalPacketFactory.KEEPALIVEPACKET) {
			keepAlivedSent = false;
			try {
				KeepAlivePacket keepAlivePacket = (KeepAlivePacket)
						LocalPacketCodec.decodeNetworkPacket(packet.getBuffer());
				if (keepAlivePacket.isToValidate()) {
					keepAlivePacket.validate();
					NetworkPacket response =
							new NetworkPacket(ChannelUtils.NOCHANNEL,
									ChannelUtils.NOCHANNEL, keepAlivePacket, null);
					logger.info("Answer KAlive");
					Channels.write(e.getChannel(), response);
				} else {
					logger.info("Get KAlive");
				}
			} catch (OpenR66ProtocolPacketException e1) {
			}
			return;
		}
		// forward message
		Channels.write(proxyChannel, packet);
	}

	@Override
	public void exceptionCaught(ChannelHandlerContext ctx, ExceptionEvent e) {
		logger.debug("Network Channel Exception: {}", e.getChannel().getId(), e
				.getCause());
		if (e.getCause() instanceof ReadTimeoutException) {
			ReadTimeoutException exception = (ReadTimeoutException) e.getCause();
			// No read for too long
			logger.error("ReadTimeout so Will close NETWORK channel {}", exception.getMessage());
			ChannelCloseTimer.closeFutureChannel(e.getChannel());
			return;
		}
		if (e.getCause() instanceof BindException) {
			// received when not yet connected
			logger.debug("BindException");
			ChannelCloseTimer.closeFutureChannel(e.getChannel());
			return;
		}
		OpenR66Exception exception = OpenR66ExceptionTrappedFactory
				.getExceptionFromTrappedException(e.getChannel(), e);
		if (exception != null) {
			if (exception instanceof OpenR66ProtocolBusinessNoWriteBackException) {
				logger.debug("Will close NETWORK channel");
				ChannelCloseTimer.closeFutureChannel(e.getChannel());
				return;
			} else if (exception instanceof OpenR66ProtocolNoConnectionException) {
				logger.debug("Connection impossible with NETWORK channel {}",
						exception.getMessage());
				Channels.close(e.getChannel());
				return;
			} else {
				logger.debug(
						"Network Channel Exception: {} {}", e.getChannel().getId(),
						exception.getMessage());
			}
			final ConnectionErrorPacket errorPacket = new ConnectionErrorPacket(
					exception.getMessage(), null);
			writeError(e.getChannel(), ChannelUtils.NOCHANNEL,
					ChannelUtils.NOCHANNEL, errorPacket);
			writeError(proxyChannel, ChannelUtils.NOCHANNEL,
					ChannelUtils.NOCHANNEL, errorPacket);
			logger.debug("Will close NETWORK channel: {}", exception.getMessage());
			ChannelCloseTimer.closeFutureChannel(e.getChannel());
		} else {
			// Nothing to do
			return;
		}
	}

	/**
	 * Write error back to remote client
	 * 
	 * @param channel
	 * @param remoteId
	 * @param localId
	 * @param error
	 */
	void writeError(Channel channel, Integer remoteId, Integer localId,
			AbstractLocalPacket error) {
		NetworkPacket networkPacket = null;
		try {
			networkPacket = new NetworkPacket(localId, remoteId, error, null);
		} catch (OpenR66ProtocolPacketException e) {
		}
		try {
			Channels.write(channel, networkPacket).await();
		} catch (InterruptedException e) {
		}
	}

	/**
	 * 
	 * @return True if this Handler is for SSL
	 */
	public boolean isSsl() {
		return isSSL;
	}
}
