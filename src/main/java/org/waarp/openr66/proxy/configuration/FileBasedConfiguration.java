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

import java.io.File;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.List;

import org.dom4j.Document;
import org.dom4j.DocumentException;
import org.dom4j.io.SAXReader;
import org.jboss.netty.handler.traffic.AbstractTrafficShapingHandler;
import org.waarp.common.crypto.Des;
import org.waarp.common.crypto.ssl.WaarpSecureKeyStore;
import org.waarp.common.crypto.ssl.WaarpSslContextFactory;
import org.waarp.common.database.DbAdmin;
import org.waarp.common.database.data.AbstractDbData.UpdatedInfo;
import org.waarp.common.database.exception.WaarpDatabaseException;
import org.waarp.common.exception.CryptoException;
import org.waarp.common.file.DirInterface;
import org.waarp.common.file.filesystembased.FilesystemBasedDirImpl;
import org.waarp.common.logging.WaarpInternalLogger;
import org.waarp.common.logging.WaarpInternalLoggerFactory;
import org.waarp.common.xml.XmlDecl;
import org.waarp.common.xml.XmlHash;
import org.waarp.common.xml.XmlType;
import org.waarp.common.xml.XmlUtil;
import org.waarp.common.xml.XmlValue;
import org.waarp.openr66.database.DbConstant;
import org.waarp.openr66.database.data.DbConfiguration;
import org.waarp.openr66.protocol.configuration.Configuration;
import org.waarp.openr66.protocol.exception.OpenR66ProtocolSystemException;
import org.waarp.openr66.protocol.networkhandler.R66ConstraintLimitHandler;
import org.waarp.openr66.protocol.networkhandler.ssl.NetworkSslServerPipelineFactory;
import org.waarp.openr66.protocol.utils.FileUtils;
import org.waarp.openr66.proxy.network.ProxyEntry;
import org.waarp.snmp.SnmpConfiguration;

/**
 * File Based Configuration
 * 
 * @author frederic bregier
 * 
 */
public class FileBasedConfiguration {
	/**
	 * Internal Logger
	 */
	private static final WaarpInternalLogger logger = WaarpInternalLoggerFactory
			.getLogger(FileBasedConfiguration.class);

	/**
	 * SERVER HOSTID
	 */
	private static final String XML_SERVER_HOSTID = "hostid";

	/**
	 * SERVER SSL HOSTID
	 */
	private static final String XML_SERVER_SSLHOSTID = "sslhostid";

	/**
	 * ADMINISTRATOR SERVER NAME (shutdown)
	 */
	private static final String XML_SERVER_ADMIN = "serveradmin";

	/**
	 * SERVER PASSWORD (shutdown)
	 */
	private static final String XML_SERVER_PASSWD = "serverpasswd";
	/**
	 * SERVER PASSWORD FILE (shutdown)
	 */
	private static final String XML_SERVER_PASSWD_FILE = "serverpasswdfile";
	/**
	 * Authentication
	 */
	private static final String XML_AUTHENTIFICATION_FILE = "authentfile";

	/**
	 * SERVER Association entry
	 */
	private static final String XML_SERVER_PROXY = "serverproxy";
	/**
	 * SERVER Listening address
	 */
	private static final String XML_SERVER_LISTENADDR = "serverlistenaddr";
	/**
	 * SERVER Listening port
	 */
	private static final String XML_SERVER_LISTENPORT = "serverlistenport";
	/**
	 * SERVER Listening ssl mode
	 */
	private static final String XML_SERVER_LISTENSSL = "serverlistenssl";
	/**
	 * SERVER Remote address
	 */
	private static final String XML_SERVER_REMOTEADDR = "serverremoteaddr";
	/**
	 * SERVER Remote PORT
	 */
	private static final String XML_SERVER_REMOTEPORT = "serverremoteport";
	/**
	 * SERVER Remote ssl mode
	 */
	private static final String XML_SERVER_REMOTESSL = "serverremotessl";
	/**
	 * SERVER HTTP PORT
	 */
	private static final String XML_SERVER_HTTPPORT = "serverhttpport";

	/**
	 * SERVER HTTP PORT
	 */
	private static final String XML_SERVER_HTTPSPORT = "serverhttpsport";

	/**
	 * SERVER SSL STOREKEY PATH
	 */
	private static final String XML_PATH_KEYPATH = "keypath";

	/**
	 * SERVER SSL KEY PASS
	 */
	private static final String XML_PATH_KEYPASS = "keypass";

	/**
	 * SERVER SSL STOREKEY PASS
	 */
	private static final String XML_PATH_KEYSTOREPASS = "keystorepass";

	/**
	 * SERVER SSL TRUSTSTOREKEY PATH
	 */
	private static final String XML_PATH_TRUSTKEYPATH = "trustkeypath";

	/**
	 * SERVER SSL TRUSTSTOREKEY PASS
	 */
	private static final String XML_PATH_TRUSTKEYSTOREPASS = "trustkeystorepass";

	/**
	 * SERVER SSL STOREKEY PATH ADMIN
	 */
	private static final String XML_PATH_ADMIN_KEYPATH = "admkeypath";

	/**
	 * SERVER SSL KEY PASS ADMIN
	 */
	private static final String XML_PATH_ADMIN_KEYPASS = "admkeypass";

	/**
	 * SERVER SSL STOREKEY PASS ADMIN
	 */
	private static final String XML_PATH_ADMIN_KEYSTOREPASS = "admkeystorepass";

	/**
	 * SERVER CRYPTO for Password
	 */
	private static final String XML_PATH_CRYPTOKEY = "cryptokey";
	/**
	 * Base Directory
	 */
	private static final String XML_SERVER_HOME = "serverhome";

	/**
	 * ARCHIVE Directory
	 */
	private static final String XML_ARCHIVEPATH = "arch";

	/**
	 * CONFIG Directory
	 */
	private static final String XML_CONFIGPATH = "conf";

	/**
	 * HTTP Admin Directory
	 */
	private static final String XML_HTTPADMINPATH = "httpadmin";
	/**
	 * Use SSL for R66 connection
	 */
	private static final String XML_USESSL = "usessl";

	/**
	 * Use non SSL for R66 connection
	 */
	private static final String XML_USENOSSL = "usenossl";

	/**
	 * Use HTTP compression for R66 HTTP connection
	 */
	private static final String XML_USEHTTPCOMP = "usehttpcomp";

	/**
	 * SERVER SSL Use TrustStore for Client Authentication
	 */
	private static final String XML_USECLIENT_AUTHENT = "trustuseclientauthenticate";

	/**
	 * Limit per session
	 */
	private static final String XML_LIMITSESSION = "sessionlimit";

	/**
	 * Limit global
	 */
	private static final String XML_LIMITGLOBAL = "globallimit";

	/**
	 * Delay between two checks for Limit
	 */
	private static final String XML_LIMITDELAY = "delaylimit";
	/**
	 * Monitoring: how long in ms to get back in monitoring
	 */
	private static final String XML_MONITOR_PASTLIMIT = "pastlimit";
	/**
	 * Monitoring: minimal interval in ms before redo real monitoring
	 */
	private static final String XML_MONITOR_MINIMALDELAY = "minimaldelay";
	/**
	 * Monitoring: snmp configuration file (if empty, no snmp support)
	 */
	private static final String XML_MONITOR_SNMP_CONFIG = "snmpconfig";
	/**
	 * Usage of CPU Limit
	 */
	private static final String XML_CSTRT_USECPULIMIT = "usecpulimit";

	/**
	 * Usage of JDK CPU Limit (True) or SysMon CPU Limit
	 */
	private static final String XML_CSTRT_USECPUJDKLIMIT = "usejdkcpulimit";

	/**
	 * CPU LIMIT between 0 and 1, where 1 stands for no limit
	 */
	private static final String XML_CSTRT_CPULIMIT = "cpulimit";
	/**
	 * Connection limit where 0 stands for no limit
	 */
	private static final String XML_CSTRT_CONNLIMIT = "connlimit";
	/**
	 * CPU LOW limit to apply increase of throttle
	 */
	private static final String XML_CSTRT_LOWCPULIMIT = "lowcpulimit";
	/**
	 * CPU HIGH limit to apply decrease of throttle, 0 meaning no throttle activated
	 */
	private static final String XML_CSTRT_HIGHCPULIMIT = "highcpulimit";
	/**
	 * PERCENTAGE DECREASE of Bandwidth
	 */
	private static final String XML_CSTRT_PERCENTDECREASE = "percentdecrease";
	/**
	 * Delay between 2 checks of throttle test
	 */
	private static final String XML_CSTRT_DELAYTHROTTLE = "delaythrottle";
	/**
	 * Bandwidth low limit to not got below
	 */
	private static final String XML_CSTRT_LIMITLOWBANDWIDTH = "limitlowbandwidth";
	/**
	 * Default number of threads in pool for Server.
	 */
	private static final String XML_SERVER_THREAD = "serverthread";

	/**
	 * Default number of threads in pool for Client (truly concurrent).
	 */
	private static final String XML_CLIENT_THREAD = "clientthread";

	/**
	 * Memory Limit to use.
	 */
	private static final String XML_MEMORY_LIMIT = "memorylimit";
	/**
	 * Delay between two retry after bad connection
	 */
	private static final String XML_DELAYRETRY = "delayretry";
	/**
	 * Nb of milliseconds after connection is in timeout
	 */
	private static final String XML_TIMEOUTCON = "timeoutcon";
	/**
	 * Check version in protocol
	 */
	private static final String XML_CHECKVERSION = "checkversion";

	/**
	 * Structure of the Configuration file
	 * 
	 */
	private static final XmlDecl[] configIdentityDecls = {
			// identity
			new XmlDecl(XmlType.STRING, XML_SERVER_HOSTID),
			new XmlDecl(XmlType.STRING, XML_SERVER_SSLHOSTID),
			new XmlDecl(XmlType.STRING, XML_PATH_CRYPTOKEY),
			new XmlDecl(XmlType.STRING, XML_AUTHENTIFICATION_FILE)
	};
	/**
	 * Structure of the Configuration file
	 * 
	 */
	private static final XmlDecl[] configServerParamDecls = {
			// server
			new XmlDecl(XmlType.BOOLEAN, XML_USESSL),
			new XmlDecl(XmlType.BOOLEAN, XML_USENOSSL),
			new XmlDecl(XmlType.BOOLEAN, XML_USEHTTPCOMP),
			new XmlDecl(XmlType.STRING, XML_SERVER_ADMIN),
			new XmlDecl(XmlType.STRING, XML_SERVER_PASSWD),
			new XmlDecl(XmlType.STRING, XML_SERVER_PASSWD_FILE),
			new XmlDecl(XmlType.STRING, XML_HTTPADMINPATH),
			new XmlDecl(XmlType.STRING, XML_PATH_ADMIN_KEYPATH),
			new XmlDecl(XmlType.STRING, XML_PATH_ADMIN_KEYSTOREPASS),
			new XmlDecl(XmlType.STRING, XML_PATH_ADMIN_KEYPASS),
			new XmlDecl(XmlType.LONG, XML_MONITOR_PASTLIMIT),
			new XmlDecl(XmlType.LONG, XML_MONITOR_MINIMALDELAY),
			new XmlDecl(XmlType.STRING, XML_MONITOR_SNMP_CONFIG)
	};
	/**
	 * Structure of the Configuration file
	 * 
	 */
	private static final XmlDecl[] configNetworkProxyDecls = {
			// proxy
			new XmlDecl(XmlType.STRING, XML_SERVER_LISTENADDR),
			new XmlDecl(XmlType.INTEGER, XML_SERVER_LISTENPORT),
			new XmlDecl(XmlType.BOOLEAN, XML_SERVER_LISTENSSL),
			new XmlDecl(XmlType.STRING, XML_SERVER_REMOTEADDR),
			new XmlDecl(XmlType.INTEGER, XML_SERVER_REMOTEPORT),
			new XmlDecl(XmlType.BOOLEAN, XML_SERVER_REMOTESSL)
	};
	/**
	 * Structure of the Configuration file
	 * 
	 */
	private static final XmlDecl[] configNetworkServerDecls = {
			// network
			new XmlDecl(XML_SERVER_PROXY, XmlType.XVAL, XML_SERVER_PROXY, configNetworkProxyDecls,
					true),
			new XmlDecl(XmlType.INTEGER, XML_SERVER_HTTPPORT),
			new XmlDecl(XmlType.INTEGER, XML_SERVER_HTTPSPORT)
	};

	/**
	 * Structure of the Configuration file
	 * 
	 */
	private static final XmlDecl[] configSslDecls = {
			// ssl
			new XmlDecl(XmlType.STRING, XML_PATH_KEYPATH),
			new XmlDecl(XmlType.STRING, XML_PATH_KEYSTOREPASS),
			new XmlDecl(XmlType.STRING, XML_PATH_KEYPASS),
			new XmlDecl(XmlType.STRING, XML_PATH_TRUSTKEYPATH),
			new XmlDecl(XmlType.STRING, XML_PATH_TRUSTKEYSTOREPASS),
			new XmlDecl(XmlType.BOOLEAN, XML_USECLIENT_AUTHENT)
	};

	/**
	 * Structure of the Configuration file
	 * 
	 */
	private static final XmlDecl[] configDirectoryDecls = {
			// directory
			new XmlDecl(XmlType.STRING, XML_SERVER_HOME),
			new XmlDecl(XmlType.STRING, XML_ARCHIVEPATH),
			new XmlDecl(XmlType.STRING, XML_CONFIGPATH)
	};

	/**
	 * Structure of the Configuration file
	 * 
	 */
	private static final XmlDecl[] configLimitDecls = {
			// limit
			new XmlDecl(XmlType.LONG, XML_LIMITSESSION),
			new XmlDecl(XmlType.LONG, XML_LIMITGLOBAL),
			new XmlDecl(XmlType.LONG, XML_LIMITDELAY),
			new XmlDecl(XmlType.LONG, XML_DELAYRETRY),
			new XmlDecl(XmlType.INTEGER, XML_SERVER_THREAD),
			new XmlDecl(XmlType.INTEGER, XML_CLIENT_THREAD),
			new XmlDecl(XmlType.LONG, XML_MEMORY_LIMIT),
			new XmlDecl(XmlType.BOOLEAN, XML_CSTRT_USECPULIMIT),
			new XmlDecl(XmlType.BOOLEAN, XML_CSTRT_USECPUJDKLIMIT),
			new XmlDecl(XmlType.DOUBLE, XML_CSTRT_CPULIMIT),
			new XmlDecl(XmlType.INTEGER, XML_CSTRT_CONNLIMIT),
			new XmlDecl(XmlType.DOUBLE, XML_CSTRT_LOWCPULIMIT),
			new XmlDecl(XmlType.DOUBLE, XML_CSTRT_HIGHCPULIMIT),
			new XmlDecl(XmlType.DOUBLE, XML_CSTRT_PERCENTDECREASE),
			new XmlDecl(XmlType.LONG, XML_CSTRT_LIMITLOWBANDWIDTH),
			new XmlDecl(XmlType.LONG, XML_CSTRT_DELAYTHROTTLE),
			new XmlDecl(XmlType.LONG, XML_TIMEOUTCON),
			new XmlDecl(XmlType.BOOLEAN, XML_CHECKVERSION)
	};

	/**
	 * Overall structure of the Configuration file
	 */
	private static final String XML_ROOT = "/config/";
	private static final String XML_IDENTITY = "identity";
	private static final String XML_SERVER = "server";
	private static final String XML_DIRECTORY = "directory";
	private static final String XML_LIMIT = "limit";
	private static final String XML_NETWORK = "network";
	private static final String XML_SSL = "ssl";

	/**
	 * Global Structure for Server Configuration
	 */
	private static final XmlDecl[] configServer = {
			new XmlDecl(XML_IDENTITY, XmlType.XVAL, XML_ROOT + XML_IDENTITY, configIdentityDecls,
					false),
			new XmlDecl(XML_SERVER, XmlType.XVAL, XML_ROOT + XML_SERVER, configServerParamDecls,
					false),
			new XmlDecl(XML_NETWORK, XmlType.XVAL, XML_ROOT + XML_NETWORK,
					configNetworkServerDecls, false),
			new XmlDecl(XML_SSL, XmlType.XVAL, XML_ROOT + XML_SSL, configSslDecls, false),
			new XmlDecl(XML_DIRECTORY, XmlType.XVAL, XML_ROOT + XML_DIRECTORY,
					configDirectoryDecls, false),
			new XmlDecl(XML_LIMIT, XmlType.XVAL, XML_ROOT + XML_LIMIT, configLimitDecls, false)
	};
	private static XmlValue[] configuration = null;
	private static XmlHash hashConfig = null;

	private static boolean loadIdentity(Configuration config) {
		XmlValue value = hashConfig.get(XML_SERVER_HOSTID);
		if (value != null && (!value.isEmpty())) {
			config.HOST_ID = value.getString();
		} else {
			logger.error("Unable to find Host ID in Config file");
			return false;
		}
		value = hashConfig.get(XML_SERVER_SSLHOSTID);
		if (value != null && (!value.isEmpty())) {
			config.HOST_SSLID = value.getString();
		} else {
			logger
					.warn("Unable to find Host SSL ID in Config file so no SSL support will be used");
			config.useSSL = false;
			config.HOST_SSLID = null;
		}
		return setCryptoKey(config);
	}

	private static boolean loadServerParam(Configuration config) {
		XmlValue value = hashConfig.get(XML_USESSL);
		if (value != null && (!value.isEmpty())) {
			config.useSSL = value.getBoolean();
		}
		value = hashConfig.get(XML_USENOSSL);
		if (value != null && (!value.isEmpty())) {
			config.useNOSSL = value.getBoolean();
		}
		value = hashConfig.get(XML_USEHTTPCOMP);
		if (value != null && (!value.isEmpty())) {
			config.useHttpCompression = value.getBoolean();
		}
		value = hashConfig.get(XML_SERVER_ADMIN);
		if (value != null && (!value.isEmpty())) {
			config.ADMINNAME = value.getString();
		} else {
			logger.error("Unable to find Administrator name in Config file");
			return false;
		}
		if (config.cryptoKey == null) {
			if (!setCryptoKey(config)) {
				logger.error("Unable to find Crypto Key in Config file");
				return false;
			}
		}
		byte[] decodedByteKeys = null;
		value = hashConfig.get(XML_SERVER_PASSWD_FILE);
		if (value == null || (value.isEmpty())) {
			String passwd;
			value = hashConfig.get(XML_SERVER_PASSWD);
			if (value != null && (!value.isEmpty())) {
				passwd = value.getString();
			} else {
				logger.error("Unable to find Password in Config file");
				return false;
			}
			try {
				decodedByteKeys =
						config.cryptoKey.decryptHexInBytes(passwd);
			} catch (Exception e) {
				logger.error(
						"Unable to Decrypt Server Password in Config file from: " +
								passwd, e);
				return false;
			}
		} else {
			String skey = value.getString();
			// load key from file
			config.serverKeyFile = skey;
			File key = new File(skey);
			if (!key.canRead()) {
				logger.error("Unable to read Password in Config file from " + skey);
				return false;
			}
			try {
				decodedByteKeys = config.cryptoKey.decryptHexFile(key);
			} catch (Exception e2) {
				logger.error(
						"Unable to Decrypt Server Password in Config file from: " +
								skey, e2);
				return false;
			}
		}
		config.setSERVERKEY(decodedByteKeys);
		value = hashConfig.get(XML_HTTPADMINPATH);
		if (value == null || (value.isEmpty())) {
			logger.error("Unable to find Http Admin Base in Config file");
			return false;
		}
		String path = value.getString();
		if (path == null || path.length() == 0) {
			logger.error("Unable to set correct Http Admin Base in Config file");
			return false;
		}
		File file = new File(path);
		if (!file.isDirectory()) {
			logger.error("Http Admin is not a directory in Config file");
			return false;
		}
		try {
			config.httpBasePath =
					FilesystemBasedDirImpl.normalizePath(file.getCanonicalPath()) +
							DirInterface.SEPARATOR;
		} catch (IOException e1) {
			logger.error("Unable to set Http Admin Path in Config file");
			return false;
		}

		// Key for HTTPS
		value = hashConfig.get(XML_PATH_ADMIN_KEYPATH);
		if (value != null && (!value.isEmpty())) {
			String keypath = value.getString();
			if ((keypath == null) || (keypath.length() == 0)) {
				logger.error("Bad Key Path");
				return false;
			}
			value = hashConfig.get(XML_PATH_ADMIN_KEYSTOREPASS);
			if (value == null || (value.isEmpty())) {
				logger.error("Unable to find KeyStore Passwd");
				return false;
			}
			String keystorepass = value.getString();
			if ((keystorepass == null) || (keystorepass.length() == 0)) {
				logger.error("Bad KeyStore Passwd");
				return false;
			}
			value = hashConfig.get(XML_PATH_ADMIN_KEYPASS);
			if (value == null || (value.isEmpty())) {
				logger.error("Unable to find Key Passwd");
				return false;
			}
			String keypass = value.getString();
			if ((keypass == null) || (keypass.length() == 0)) {
				logger.error("Bad Key Passwd");
				return false;
			}
			try {
				Configuration.WaarpSecureKeyStore =
						new WaarpSecureKeyStore(keypath, keystorepass,
								keypass);
			} catch (CryptoException e) {
				logger.error("Bad SecureKeyStore construction for AdminSsl");
				return false;
			}
			// No client authentication
			Configuration.WaarpSecureKeyStore.initEmptyTrustStore();
			Configuration.waarpSslContextFactory =
					new WaarpSslContextFactory(
							Configuration.WaarpSecureKeyStore, true);
		}
		value = hashConfig.get(XML_MONITOR_PASTLIMIT);
		if (value != null && (!value.isEmpty())) {
			config.pastLimit = (value.getLong() / 10) * 10;
		}
		value = hashConfig.get(XML_MONITOR_MINIMALDELAY);
		if (value != null && (!value.isEmpty())) {
			config.minimalDelay = (value.getLong() / 10) * 10;
		}
		value = hashConfig.get(XML_MONITOR_SNMP_CONFIG);
		if (value != null && (!value.isEmpty())) {
			config.snmpConfig = value.getString();
			File snmpfile = new File(config.snmpConfig);
			if (snmpfile.canRead()) {
				if (!SnmpConfiguration.setConfigurationFromXml(snmpfile)) {
					config.snmpConfig = null;
				}
			} else {
				config.snmpConfig = null;
			}
		}
		return true;
	}

	private static boolean loadDirectory(Configuration config) {
		XmlValue value = hashConfig.get(XML_SERVER_HOME);
		if (value == null || (value.isEmpty())) {
			logger.error("Unable to find Home in Config file");
			return false;
		}
		String path = value.getString();
		File file = new File(path);
		if (!file.isDirectory()) {
			logger.error("Home is not a directory in Config file");
			return false;
		}
		try {
			config.baseDirectory = FilesystemBasedDirImpl
					.normalizePath(file.getCanonicalPath());
		} catch (IOException e1) {
			logger.error("Unable to set Home in Config file");
			return false;
		}
		try {
			config.configPath = FilesystemBasedDirImpl
					.normalizePath(getSubPath(config, XML_CONFIGPATH));
		} catch (OpenR66ProtocolSystemException e2) {
			logger.error("Unable to set Config in Config file");
			return false;
		}
		try {
			config.archivePath = FilesystemBasedDirImpl
					.normalizePath(getSubPath(config, XML_ARCHIVEPATH));
		} catch (OpenR66ProtocolSystemException e2) {
			logger.error("Unable to set Archive in Config file");
			return false;
		}
		return true;
	}

	private static boolean alreadySetLimit = false;

	private static boolean loadLimit(Configuration config, boolean updateLimit) {
		if (alreadySetLimit) {
			return true;
		}
		XmlValue value = hashConfig.get(XML_LIMITGLOBAL);
		if (value != null && (!value.isEmpty())) {
			config.serverGlobalReadLimit = value.getLong();
			if (config.serverGlobalReadLimit <= 0) {
				config.serverGlobalReadLimit = 0;
			}
			config.serverGlobalWriteLimit = config.serverGlobalReadLimit;
			logger.info("Global Limit: {}",
					config.serverGlobalReadLimit);
		}
		value = hashConfig.get(XML_LIMITSESSION);
		if (value != null && (!value.isEmpty())) {
			config.serverChannelReadLimit = value.getLong();
			if (config.serverChannelReadLimit <= 0) {
				config.serverChannelReadLimit = 0;
			}
			config.serverChannelWriteLimit = config.serverChannelReadLimit;
			logger.info("SessionInterface Limit: {}",
					config.serverChannelReadLimit);
		}
		config.anyBandwidthLimitation =
				(config.serverGlobalReadLimit > 0 || config.serverGlobalWriteLimit > 0 ||
						config.serverChannelReadLimit > 0 || config.serverChannelWriteLimit > 0);
		config.delayLimit = AbstractTrafficShapingHandler.DEFAULT_CHECK_INTERVAL;
		value = hashConfig.get(XML_LIMITDELAY);
		if (value != null && (!value.isEmpty())) {
			config.delayLimit = (value.getLong() / 10) * 10;
			if (config.delayLimit <= 0) {
				config.delayLimit = 0;
			}
			logger.info("Delay Limit: {}",
					config.delayLimit);
		}
		value = hashConfig.get(XML_DELAYRETRY);
		if (value != null && (!value.isEmpty())) {
			config.delayRetry = (value.getLong() / 10) * 10;
			if (config.delayRetry <= 1000) {
				config.delayRetry = 1000;
			}
			logger.info("Delay Retry: {}",
					config.delayRetry);
		}
		if (config.RUNNER_THREAD < 10) {
			config.RUNNER_THREAD = 10;
		}
		logger.info("Limit of Runner: {}",
				config.RUNNER_THREAD);
		if (DbConstant.admin.isConnected && updateLimit) {
			value = hashConfig.get(XML_SERVER_HOSTID);
			if (value != null && (!value.isEmpty())) {
				config.HOST_ID = value.getString();
				DbConfiguration configuration = new DbConfiguration(
						DbConstant.admin.session,
						config.HOST_ID,
						config.serverGlobalReadLimit,
						config.serverGlobalWriteLimit,
						config.serverChannelReadLimit,
						config.serverChannelWriteLimit,
						config.delayLimit);
				configuration.changeUpdatedInfo(UpdatedInfo.TOSUBMIT);
				try {
					if (configuration.exist()) {
						configuration.update();
					} else {
						configuration.insert();
					}
				} catch (WaarpDatabaseException e) {
				}
			}
		}
		boolean useCpuLimit = false;
		boolean useCpuLimitJDK = false;
		double cpulimit = 1.0;
		value = hashConfig.get(XML_CSTRT_USECPULIMIT);
		if (value != null && (!value.isEmpty())) {
			useCpuLimit = value.getBoolean();
			value = hashConfig.get(XML_CSTRT_USECPUJDKLIMIT);
			if (value != null && (!value.isEmpty())) {
				useCpuLimitJDK = value.getBoolean();
			}
			value = hashConfig.get(XML_CSTRT_CPULIMIT);
			if (value != null && (!value.isEmpty())) {
				cpulimit = value.getDouble();
			}
		}
		int connlimit = 0;
		value = hashConfig.get(XML_CSTRT_CONNLIMIT);
		if (value != null && (!value.isEmpty())) {
			connlimit = value.getInteger();
		}
		double lowcpuLimit = 0;
		double highcpuLimit = 0;
		double percentageDecrease = 0;
		long delay = 1000000;
		long limitLowBandwidth = 4096;
		value = hashConfig.get(XML_CSTRT_LOWCPULIMIT);
		if (value != null && (!value.isEmpty())) {
			lowcpuLimit = value.getDouble();
		}
		value = hashConfig.get(XML_CSTRT_HIGHCPULIMIT);
		if (value != null && (!value.isEmpty())) {
			highcpuLimit = value.getDouble();
		}
		value = hashConfig.get(XML_CSTRT_PERCENTDECREASE);
		if (value != null && (!value.isEmpty())) {
			percentageDecrease = value.getDouble();
		}
		value = hashConfig.get(XML_CSTRT_DELAYTHROTTLE);
		if (value != null && (!value.isEmpty())) {
			delay = (value.getLong() / 10) * 10;
		}
		value = hashConfig.get(XML_CSTRT_LIMITLOWBANDWIDTH);
		if (value != null && (!value.isEmpty())) {
			limitLowBandwidth = value.getLong();
		}
		if (highcpuLimit > 0) {
			config.constraintLimitHandler =
					new R66ConstraintLimitHandler(useCpuLimit, useCpuLimitJDK, cpulimit, connlimit,
							lowcpuLimit, highcpuLimit, percentageDecrease, null, delay,
							limitLowBandwidth);
		} else {
			config.constraintLimitHandler =
					new R66ConstraintLimitHandler(useCpuLimit, useCpuLimitJDK, cpulimit, connlimit);
		}
		value = hashConfig.get(XML_SERVER_THREAD);
		if (value != null && (!value.isEmpty())) {
			config.SERVER_THREAD = value.getInteger();
		}
		value = hashConfig.get(XML_CLIENT_THREAD);
		if (value != null && (!value.isEmpty())) {
			config.CLIENT_THREAD = value.getInteger();
		}
		value = hashConfig.get(XML_MEMORY_LIMIT);
		if (value != null && (!value.isEmpty())) {
			config.maxGlobalMemory = value.getLong();
		}
		Configuration.getFileParameter().deleteOnAbort = false;
		value = hashConfig.get(XML_TIMEOUTCON);
		if (value != null && (!value.isEmpty())) {
			config.TIMEOUTCON = (value.getLong() / 10) * 10;
			config.shutdownConfiguration.timeout = config.TIMEOUTCON;
		}
		alreadySetLimit = true;
		return true;
	}

	private static boolean loadSsl(Configuration config) {
		// StoreKey for Server
		XmlValue value = hashConfig.get(XML_PATH_KEYPATH);
		if (value == null || (value.isEmpty())) {
			logger.info("Unable to find Key Path");
			try {
				NetworkSslServerPipelineFactory.WaarpSecureKeyStore =
						new WaarpSecureKeyStore("secret", "secret");
			} catch (CryptoException e) {
				logger.error("Bad SecureKeyStore construction");
				return false;
			}
		} else {
			String keypath = value.getString();
			if ((keypath == null) || (keypath.length() == 0)) {
				logger.error("Bad Key Path");
				return false;
			}
			value = hashConfig.get(XML_PATH_KEYSTOREPASS);
			if (value == null || (value.isEmpty())) {
				logger.error("Unable to find KeyStore Passwd");
				return false;
			}
			String keystorepass = value.getString();
			if ((keystorepass == null) || (keystorepass.length() == 0)) {
				logger.error("Bad KeyStore Passwd");
				return false;
			}
			value = hashConfig.get(XML_PATH_KEYPASS);
			if (value == null || (value.isEmpty())) {
				logger.error("Unable to find Key Passwd");
				return false;
			}
			String keypass = value.getString();
			if ((keypass == null) || (keypass.length() == 0)) {
				logger.error("Bad Key Passwd");
				return false;
			}
			try {
				NetworkSslServerPipelineFactory.WaarpSecureKeyStore =
						new WaarpSecureKeyStore(keypath, keystorepass,
								keypass);
			} catch (CryptoException e) {
				logger.error("Bad SecureKeyStore construction");
				return false;
			}

		}
		// TrustedKey for OpenR66 server
		value = hashConfig.get(XML_PATH_TRUSTKEYPATH);
		if (value == null || (value.isEmpty())) {
			logger.info("Unable to find TRUST Key Path");
			NetworkSslServerPipelineFactory.WaarpSecureKeyStore.initEmptyTrustStore();
		} else {
			String keypath = value.getString();
			if ((keypath == null) || (keypath.length() == 0)) {
				logger.error("Bad TRUST Key Path");
				return false;
			}
			value = hashConfig.get(XML_PATH_TRUSTKEYSTOREPASS);
			if (value == null || (value.isEmpty())) {
				logger.error("Unable to find TRUST KeyStore Passwd");
				return false;
			}
			String keystorepass = value.getString();
			if ((keystorepass == null) || (keystorepass.length() == 0)) {
				logger.error("Bad TRUST KeyStore Passwd");
				return false;
			}
			boolean useClientAuthent = false;
			value = hashConfig.get(XML_USECLIENT_AUTHENT);
			if (value != null && (!value.isEmpty())) {
				useClientAuthent = value.getBoolean();
			}
			try {
				NetworkSslServerPipelineFactory.WaarpSecureKeyStore.initTrustStore(keypath,
						keystorepass, useClientAuthent);
			} catch (CryptoException e) {
				logger.error("Bad TrustKeyStore construction");
				return false;
			}
		}
		NetworkSslServerPipelineFactory.waarpSslContextFactory =
				new WaarpSslContextFactory(
						NetworkSslServerPipelineFactory.WaarpSecureKeyStore);
		return true;
	}

	@SuppressWarnings("unchecked")
	private static boolean loadNetworkServer(Configuration config) {
		XmlValue value = hashConfig.get(XML_SERVER_HTTPPORT);
		int httpport = 8066;
		if (value != null && (!value.isEmpty())) {
			httpport = value.getInteger();
		}
		config.SERVER_HTTPPORT = httpport;
		value = hashConfig.get(XML_SERVER_HTTPSPORT);
		int httpsport = 8067;
		if (value != null && (!value.isEmpty())) {
			httpsport = value.getInteger();
		}
		config.SERVER_HTTPSPORT = httpsport;
		// XXX FIXME to change to accept multiple port according to relation local-proxy
		value = hashConfig.get(XML_SERVER_PROXY);
		if (value != null && (value.getList() != null)) {
			for (XmlValue[] xml : (List<XmlValue[]>) value.getList()) {
				XmlHash subHash = new XmlHash(xml);
				value = subHash.get(XML_SERVER_LISTENADDR);
				if (value == null || (value.isEmpty())) {
					continue;
				}
				String listenaddr = value.getString();
				value = subHash.get(XML_SERVER_LISTENPORT);
				if (value == null || (value.isEmpty())) {
					continue;
				}
				int listenport = value.getInteger();
				value = subHash.get(XML_SERVER_LISTENSSL);
				if (value == null || (value.isEmpty())) {
					continue;
				}
				boolean listenssl = value.getBoolean();
				value = subHash.get(XML_SERVER_REMOTEADDR);
				if (value == null || (value.isEmpty())) {
					continue;
				}
				String remoteaddr = value.getString();
				value = subHash.get(XML_SERVER_REMOTEPORT);
				if (value == null || (value.isEmpty())) {
					continue;
				}
				int remoteport = value.getInteger();
				value = subHash.get(XML_SERVER_REMOTESSL);
				if (value == null || (value.isEmpty())) {
					continue;
				}
				boolean remotessl = value.getBoolean();
				InetSocketAddress local = new InetSocketAddress(listenaddr, listenport);
				InetSocketAddress remote = new InetSocketAddress(remoteaddr, remoteport);
				ProxyEntry.add(local, listenssl, remote, remotessl);
			}
			if (ProxyEntry.proxyEntries.isEmpty()) {
				logger.error("No proxy configuration found");
				return false;
			}
		} else {
			logger.error("No proxy configuration found");
			return false;
		}
		return true;
	}

	/**
	 * Set the Crypto Key from the Document
	 * 
	 * @param document
	 * @return True if OK
	 */
	private static boolean setCryptoKey(Configuration config) {
		XmlValue value = hashConfig.get(XML_PATH_CRYPTOKEY);
		if (value == null || (value.isEmpty())) {
			logger.error("Unable to find CryptoKey in Config file");
			return false;
		}
		String filename = value.getString();
		config.cryptoFile = filename;
		File key = new File(filename);
		Des des = new Des();
		try {
			des.setSecretKey(key);
		} catch (CryptoException e) {
			logger.error("Unable to load CryptoKey from Config file");
			return false;
		} catch (IOException e) {
			logger.error("Unable to load CryptoKey from Config file");
			return false;
		}
		config.cryptoKey = des;
		return true;
	}

	/**
	 * Load database parameter
	 * 
	 * @param document
	 * @return True if OK
	 */
	private static boolean loadDatabase(Configuration config) {
		logger.info("Unable to find DBDriver in Config file");
		DbConstant.admin = new DbAdmin(); // no database support
		DbConstant.noCommitAdmin = DbConstant.admin;
		return true;
	}

	/**
	 * 
	 * @param document
	 * @param fromXML
	 * @return the new subpath
	 * @throws OpenR66ProtocolSystemException
	 */
	private static String getSubPath(Configuration config, String fromXML)
			throws OpenR66ProtocolSystemException {
		XmlValue value = hashConfig.get(fromXML);
		if (value == null || (value.isEmpty())) {
			logger.error("Unable to find a Path in Config file: " + fromXML);
			throw new OpenR66ProtocolSystemException(
					"Unable to find a Path in Config file: " + fromXML);
		}

		String path = value.getString();
		if (path == null || path.length() == 0) {
			throw new OpenR66ProtocolSystemException(
					"Unable to find a correct Path in Config file: " + fromXML);
		}
		path = DirInterface.SEPARATOR + path;
		String newpath = config.baseDirectory + path;
		File file = new File(newpath);
		if (!file.isDirectory()) {
			FileUtils.createDir(file);
		}
		return path;
	}

	/**
	 * Initiate the configuration from the xml file for server
	 * 
	 * @param filename
	 * @return True if OK
	 */
	public static boolean setConfigurationProxyFromXml(Configuration config, String filename) {
		Document document = null;
		// Open config file
		try {
			document = new SAXReader().read(filename);
		} catch (DocumentException e) {
			logger.error("Unable to read the XML Config file: " + filename, e);
			return false;
		}
		if (document == null) {
			logger.error("Unable to read the XML Config file: " + filename);
			return false;
		}
		configuration = XmlUtil.read(document, configServer);
		hashConfig = new XmlHash(configuration);
		// Now read the configuration
		if (!loadIdentity(config)) {
			logger.error("Cannot load Identity");
			return false;
		}
		if (!loadDatabase(config)) {
			logger.error("Cannot load Database configuration");
			return false;
		}
		if (!loadServerParam(config)) {
			logger.error("Cannot load Server Parameters");
			return false;
		}
		if (!loadDirectory(config)) {
			logger.error("Cannot load Directory configuration");
			return false;
		}
		if (!loadLimit(config, false)) {
			logger.error("Cannot load Limit configuration");
			return false;
		}
		if (config.useSSL) {
			if (!loadSsl(config)) {
				logger.error("Cannot load SSL configuration");
				return false;
			}
		}
		if (!loadNetworkServer(config)) {
			logger.error("Cannot load Network configuration");
			return false;
		}
		hashConfig.clear();
		hashConfig = null;
		configuration = null;
		return true;
	}

}
