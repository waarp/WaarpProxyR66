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

import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.ChannelFuture;
import io.netty.channel.group.DefaultChannelGroup;

import org.waarp.common.database.exception.WaarpDatabaseSqlException;
import org.waarp.common.logging.WaarpLogger;
import org.waarp.common.logging.WaarpLoggerFactory;
import org.waarp.common.utility.WaarpNettyUtil;
import org.waarp.openr66.protocol.configuration.Messages;
import org.waarp.openr66.protocol.networkhandler.GlobalTrafficHandler;
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
    public void serverStartup() {
        isServer = true;
        shutdownConfiguration.timeout = TIMEOUTCON;
        R66ShutdownHook.addShutdownHook();
        if ((!useNOSSL) && (!useSSL)) {
            logger.error("OpenR66 has neither NOSSL nor SSL support included! Stop here!");
            System.exit(-1);
        }
        pipelineInit();
        serverPipelineInit();
        r66Startup();
        startHttpSupport();
        try {
            startMonitoring();
        } catch (WaarpDatabaseSqlException e) {
        }
        logger.warn(this.toString());
    }

    @Override
    public void r66Startup() {
        logger.debug("Start R66: " + SERVER_PORT + ":" + useNOSSL + " " + SERVER_SSLPORT + ":"
                + useSSL + ":" + HOST_SSLID);
        // add into configuration
        this.constraintLimitHandler.setServer(true);
        // Global Server
        serverChannelGroup = new DefaultChannelGroup("OpenR66", subTaskGroup.next());
        if (useNOSSL) {
            serverBootstrap = new ServerBootstrap();
            WaarpNettyUtil.setServerBootstrap(serverBootstrap, bossGroup, workerGroup, (int) TIMEOUTCON);
            networkServerInitializer = new NetworkServerInitializer(true);
            serverBootstrap.childHandler(networkServerInitializer);
            // FIXME take into account multiple address
            for (ProxyEntry entry : ProxyEntry.proxyEntries.values()) {
                if (!entry.isLocalSsl()) {
                    ChannelFuture future = serverBootstrap.bind(entry.getLocalSocketAddress()).awaitUninterruptibly();
                    if (future.isSuccess()) {
                        bindNoSSL = future.channel();
                        serverChannelGroup.add(bindNoSSL);
                    } else {
                        logger.warn(Messages.getString("Configuration.NOSSLDeactivated")
                                + " for " + entry.getLocalSocketAddress()); //$NON-NLS-1$
                    }
                }
            }
        } else {
            networkServerInitializer = null;
            logger.warn(Messages.getString("Configuration.NOSSLDeactivated")); //$NON-NLS-1$
        }

        if (useSSL && HOST_SSLID != null) {
            serverSslBootstrap = new ServerBootstrap();
            WaarpNettyUtil.setServerBootstrap(serverSslBootstrap, bossGroup, workerGroup, (int) TIMEOUTCON);
            networkSslServerInitializer = new NetworkSslServerInitializer(false);
            serverSslBootstrap.childHandler(networkSslServerInitializer);
            // FIXME take into account multiple address
            for (ProxyEntry entry : ProxyEntry.proxyEntries.values()) {
                if (entry.isLocalSsl()) {
                    ChannelFuture future = serverSslBootstrap.bind(entry.getLocalSocketAddress())
                            .awaitUninterruptibly();
                    if (future.isSuccess()) {
                        bindSSL = future.channel();
                        serverChannelGroup.add(bindSSL);
                    } else {
                        logger.warn(Messages.getString("Configuration.SSLMODEDeactivated")
                                + " for " + entry.getLocalSocketAddress()); //$NON-NLS-1$
                    }
                }
            }
        } else {
            networkSslServerInitializer = null;
            logger.warn(Messages.getString("Configuration.SSLMODEDeactivated")); //$NON-NLS-1$
        }

        // Factory for TrafficShapingHandler
        globalTrafficShapingHandler = new GlobalTrafficHandler(subTaskGroup, serverGlobalWriteLimit,
                serverGlobalReadLimit, serverChannelWriteLimit, serverChannelReadLimit, delayLimit);
        this.constraintLimitHandler.setHandler(globalTrafficShapingHandler);
        ProxyBridge.initialize();
        localTransaction = new LocalTransaction();
        thriftService = null;
    }

    @Override
    public void startHttpSupport() {
        // Now start the HTTP support
        logger.info(Messages.getString("Configuration.HTTPStart") + SERVER_HTTPPORT + //$NON-NLS-1$
                " HTTPS: " + SERVER_HTTPSPORT);
        httpChannelGroup = new DefaultChannelGroup("HttpOpenR66", subTaskGroup.next());
        // Configure the server.
        httpBootstrap = new ServerBootstrap();
        WaarpNettyUtil.setServerBootstrap(httpBootstrap, httpBossGroup, httpWorkerGroup, (int) TIMEOUTCON);
        // Set up the event pipeline factory.
        httpBootstrap.childHandler(new HttpInitializer(useHttpCompression));
        // Bind and start to accept incoming connections.
        if (SERVER_HTTPPORT > 0) {
            ChannelFuture future = httpBootstrap.bind(new InetSocketAddress(SERVER_HTTPPORT)).awaitUninterruptibly();
            if (future.isSuccess()) {
                httpChannelGroup.add(future.channel());
            }
        }
        // Now start the HTTPS support
        // Configure the server.
        httpsBootstrap = new ServerBootstrap();
        // Set up the event pipeline factory.
        WaarpNettyUtil.setServerBootstrap(httpsBootstrap, httpBossGroup, httpWorkerGroup, (int) TIMEOUTCON);
        httpsBootstrap.childHandler(new HttpSslInitializer(useHttpCompression, false));
        // Bind and start to accept incoming connections.
        if (SERVER_HTTPSPORT > 0) {
            ChannelFuture future = httpsBootstrap.bind(new InetSocketAddress(SERVER_HTTPSPORT)).awaitUninterruptibly();
            if (future.isSuccess()) {
                httpChannelGroup.add(future.channel());
            }
        }
    }

    @Override
    public void serverStop() {
        super.serverStop();
        ProxyBridge.transaction.closeAll();
    }

}
