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
package org.waarp.openr66.proxy.configuration;

import java.net.InetSocketAddress;
import java.util.concurrent.TimeUnit;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.group.DefaultChannelGroup;
import io.netty.channel.socket.nio.NioServerSocketChannelFactory;
import io.netty.handler.execution.OrderedMemoryAwareThreadPoolExecutor;
import org.waarp.common.database.exception.WaarpDatabaseSqlException;
import org.waarp.common.logging.WaarpLogger;
import org.waarp.common.logging.WaarpLoggerFactory;
import org.waarp.common.utility.WaarpThreadFactory;
import org.waarp.openr66.protocol.networkhandler.GlobalTrafficHandler;
import org.waarp.openr66.protocol.networkhandler.packet.NetworkPacketSizeEstimator;
import org.waarp.openr66.protocol.utils.R66ShutdownHook;
import org.waarp.openr66.proxy.network.LocalTransaction;
import org.waarp.openr66.proxy.network.NetworkServerInitializer;
import org.waarp.openr66.proxy.network.ProxyBridge;
import org.waarp.openr66.proxy.network.ProxyEntry;
import org.waarp.openr66.proxy.network.ssl.NetworkSslServerInitializer;
import org.waarp.openr66.proxy.protocol.http.HttpInitializer;
import org.waarp.openr66.proxy.protocol.http.adminssl.HttpSslInitializer;

/**
 * @author "Frederic Bregier"
 * 
 */
public class Configuration extends org.waarp.openr66.protocol.configuration.Configuration {
	/**
	 * Internal Logger
	 */
	private static final WaarpLogger logger = WaarpLoggerFactory
			.getLogger(Configuration.class);

	/**
	 * 
	 */
	public Configuration() {
		super();
	}

	@Override
	public void computeNbThreads() {
		int nb = Runtime.getRuntime().availableProcessors() + 1;
		SERVER_THREAD = nb;
		CLIENT_THREAD = SERVER_THREAD + 1;
		RUNNER_THREAD = 10;
	}

	@Override
	public void pipelineInit() {
		if (configured) {
			return;
		}
		WaarpLoggerFactory.setDefaultFactory(WaarpLoggerFactory
				.getDefaultFactory());
		objectSizeEstimator = new NetworkPacketSizeEstimator();
		httpPipelineInit();
		logger.warn("Server Thread: " + SERVER_THREAD + " Client Thread: " + CLIENT_THREAD
				+ " Runner Thread: " + RUNNER_THREAD);
		serverPipelineExecutor = new OrderedMemoryAwareThreadPoolExecutor(
				CLIENT_THREAD, maxGlobalMemory / 10, maxGlobalMemory, 1000,
				TimeUnit.MILLISECONDS, objectSizeEstimator,
				new WaarpThreadFactory("ServerExecutor"));
		configured = true;
	}

	@Override
	public void serverStartup() {
		isServer = true;
		shutdownConfiguration.timeout = TIMEOUTCON;
		R66ShutdownHook.addShutdownHook();
		if ((!useNOSSL) && (!useSSL)) {
			logger.error("OpenR66 has neither NOSSL nor SSL support included! Stop here!");
			System.exit(-1);
		}
		pipelineInit();
		r66Startup();
		startHttpSupport();
		try {
			startMonitoring();
		} catch (WaarpDatabaseSqlException e) {
		}
	}

	@Override
	public void r66Startup() {
		logger.debug("Start R66: " + SERVER_PORT + ":" + useNOSSL + " " + SERVER_SSLPORT + ":"
				+ useSSL + ":" + HOST_SSLID);
		// add into configuration
		this.constraintLimitHandler.setServer(true);
		// Global Server
		serverChannelGroup = new DefaultChannelGroup("OpenR66");

		serverChannelFactory = new NioServerSocketChannelFactory(
				execServerBoss, execServerWorker, SERVER_THREAD);
		if (useNOSSL) {
			serverBootstrap = new ServerBootstrap(serverChannelFactory);
			networkServerInitializer = new NetworkServerInitializer(true);
			serverBootstrap.setInitializer(networkServerInitializer);
			serverBootstrap.setOption("child.tcpNoDelay", true);
			serverBootstrap.setOption("child.keepAlive", true);
			serverBootstrap.setOption("child.reuseAddress", true);
			serverBootstrap.setOption("child.connectTimeoutMillis", TIMEOUTCON);
			serverBootstrap.setOption("tcpNoDelay", true);
			serverBootstrap.setOption("reuseAddress", true);
			serverBootstrap.setOption("connectTimeoutMillis", TIMEOUTCON);
			// FIXME take into account multiple address
			for (ProxyEntry entry : ProxyEntry.proxyEntries.values()) {
				if (!entry.isLocalSsl()) {
					serverChannelGroup.add(serverBootstrap.bind(entry.getLocalSocketAddress()));
				}
			}
		} else {
			networkServerInitializer = null;
			logger.warn("NOSSL mode is deactivated");
		}

		if (useSSL && HOST_SSLID != null) {
			serverSslBootstrap = new ServerBootstrap(serverChannelFactory);
			networkSslServerInitializer = new NetworkSslServerInitializer(false);
			serverSslBootstrap.setInitializer(networkSslServerInitializer);
			serverSslBootstrap.setOption("child.tcpNoDelay", true);
			serverSslBootstrap.setOption("child.keepAlive", true);
			serverSslBootstrap.setOption("child.reuseAddress", true);
			serverSslBootstrap.setOption("child.connectTimeoutMillis", TIMEOUTCON);
			serverSslBootstrap.setOption("tcpNoDelay", true);
			serverSslBootstrap.setOption("reuseAddress", true);
			serverSslBootstrap.setOption("connectTimeoutMillis", TIMEOUTCON);
			// FIXME take into account multiple address
			for (ProxyEntry entry : ProxyEntry.proxyEntries.values()) {
				if (entry.isLocalSsl()) {
					serverChannelGroup.add(serverSslBootstrap.bind(entry.getLocalSocketAddress()));
				}
			}
		} else {
			networkSslServerInitializer = null;
			logger.warn("SSL mode is desactivated");
		}

		// Factory for TrafficShapingHandler
		globalTrafficShapingHandler = new GlobalTrafficHandler(
				objectSizeEstimator, timerTrafficCounter,
				serverGlobalWriteLimit, serverGlobalReadLimit, delayLimit);
		this.constraintLimitHandler.setHandler(globalTrafficShapingHandler);
		ProxyBridge.initialize();
		localTransaction = new LocalTransaction();
		thriftService = null;
	}

	@Override
	public void startHttpSupport() {
		// Now start the HTTP support
		httpChannelGroup = new DefaultChannelGroup("HttpOpenR66");
		// Configure the server.
		httpChannelFactory = new NioServerSocketChannelFactory(
				execServerBoss,
				execServerWorker,
				SERVER_THREAD);
		httpBootstrap = new ServerBootstrap(
				httpChannelFactory);
		// Set up the event pipeline factory.
		httpBootstrap.setInitializer(new HttpInitializer(useHttpCompression));
		httpBootstrap.setOption("child.tcpNoDelay", true);
		httpBootstrap.setOption("child.keepAlive", true);
		httpBootstrap.setOption("child.reuseAddress", true);
		httpBootstrap.setOption("child.connectTimeoutMillis", TIMEOUTCON);
		httpBootstrap.setOption("tcpNoDelay", true);
		httpBootstrap.setOption("reuseAddress", true);
		httpBootstrap.setOption("connectTimeoutMillis", TIMEOUTCON);
		// Bind and start to accept incoming connections.
		httpChannelGroup.add(httpBootstrap.bind(new InetSocketAddress(SERVER_HTTPPORT)));

		// Now start the HTTPS support
		// Configure the server.
		httpsChannelFactory = new NioServerSocketChannelFactory(
				execServerBoss,
				execServerWorker,
				SERVER_THREAD);
		httpsBootstrap = new ServerBootstrap(
				httpsChannelFactory);
		// Set up the event pipeline factory.
		httpsBootstrap.setInitializer(new HttpSslInitializer(useHttpCompression,
				false));
		httpsBootstrap.setOption("child.tcpNoDelay", true);
		httpsBootstrap.setOption("child.keepAlive", true);
		httpsBootstrap.setOption("child.reuseAddress", true);
		httpsBootstrap.setOption("child.connectTimeoutMillis", TIMEOUTCON);
		httpsBootstrap.setOption("tcpNoDelay", true);
		httpsBootstrap.setOption("reuseAddress", true);
		httpsBootstrap.setOption("connectTimeoutMillis", TIMEOUTCON);
		// Bind and start to accept incoming connections.
		httpChannelGroup.add(httpsBootstrap.bind(new InetSocketAddress(SERVER_HTTPSPORT)));
	}

	@Override
	public void serverStop() {
		super.serverStop();
		ProxyBridge.transaction.closeAll();
	}

}
