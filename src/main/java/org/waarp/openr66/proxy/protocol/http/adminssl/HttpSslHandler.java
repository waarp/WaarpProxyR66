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
package org.waarp.openr66.proxy.protocol.http.adminssl;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.jboss.netty.buffer.ChannelBuffer;
import org.jboss.netty.buffer.ChannelBuffers;
import org.jboss.netty.channel.Channel;
import org.jboss.netty.channel.ChannelFuture;
import org.jboss.netty.channel.ChannelHandlerContext;
import org.jboss.netty.channel.ChannelStateEvent;
import org.jboss.netty.channel.ExceptionEvent;
import org.jboss.netty.channel.MessageEvent;
import org.jboss.netty.channel.SimpleChannelUpstreamHandler;
import org.jboss.netty.handler.codec.http.Cookie;
import org.jboss.netty.handler.codec.http.CookieDecoder;
import org.jboss.netty.handler.codec.http.CookieEncoder;
import org.jboss.netty.handler.codec.http.DefaultCookie;
import org.jboss.netty.handler.codec.http.DefaultHttpResponse;
import org.jboss.netty.handler.codec.http.HttpHeaders;
import org.jboss.netty.handler.codec.http.HttpMethod;
import org.jboss.netty.handler.codec.http.HttpRequest;
import org.jboss.netty.handler.codec.http.HttpResponse;
import org.jboss.netty.handler.codec.http.HttpResponseStatus;
import org.jboss.netty.handler.codec.http.HttpVersion;
import org.jboss.netty.handler.codec.http.QueryStringDecoder;
import org.jboss.netty.handler.traffic.TrafficCounter;
import org.waarp.common.crypto.ssl.WaarpSslUtility;
import org.waarp.common.exception.FileTransferException;
import org.waarp.common.exception.InvalidArgumentException;
import org.waarp.common.logging.WaarpInternalLogger;
import org.waarp.common.logging.WaarpInternalLoggerFactory;
import org.waarp.common.utility.WaarpStringUtils;
import org.waarp.openr66.context.R66Session;
import org.waarp.openr66.protocol.configuration.Messages;
import org.waarp.openr66.protocol.exception.OpenR66Exception;
import org.waarp.openr66.protocol.exception.OpenR66ExceptionTrappedFactory;
import org.waarp.openr66.protocol.exception.OpenR66ProtocolBusinessNoWriteBackException;
import org.waarp.openr66.protocol.http.HttpWriteCacheEnable;
import org.waarp.openr66.protocol.utils.ChannelUtils;
import org.waarp.openr66.protocol.utils.R66ShutdownHook;
import org.waarp.openr66.protocol.utils.Version;
import org.waarp.openr66.proxy.configuration.Configuration;

/**
 * @author Frederic Bregier
 * 
 */
public class HttpSslHandler extends SimpleChannelUpstreamHandler {
	/**
	 * Internal Logger
	 */
	private static final WaarpInternalLogger logger = WaarpInternalLoggerFactory
			.getLogger(HttpSslHandler.class);
	/**
	 * Session Management
	 */
	private static final ConcurrentHashMap<String, R66Session> sessions = new ConcurrentHashMap<String, R66Session>();
	private static final Random random = new Random();
	
	private volatile R66Session authentHttp = new R66Session();

	private volatile HttpRequest request;
	private volatile boolean newSession = false;
	private volatile Cookie admin = null;
	private final StringBuilder responseContent = new StringBuilder();
	private volatile String uriRequest;
	private volatile Map<String, List<String>> params;
	private volatile String lang = Messages.slocale;
	private volatile QueryStringDecoder queryStringDecoder;
	private volatile boolean forceClose = false;
	private volatile boolean shutdown = false;

	private static final String R66SESSION = "R66SESSION";
	private static final String I18NEXT = "i18next";

	private static enum REQUEST {
		Logon("Logon.html"),
		index("index.html"),
		error("error.html"),
		System("System.html");

		private String header;

		/**
		 * Constructor for a unique file
		 * 
		 * @param uniquefile
		 */
		private REQUEST(String uniquefile) {
			this.header = uniquefile;
		}

		/**
		 * Reader for a unique file
		 * 
		 * @return the content of the unique file
		 */
		public String readFileUnique(HttpSslHandler handler) {
			return handler.readFileHeader(Configuration.configuration.httpBasePath + this.header);
		}
	}

	private static enum REPLACEMENT {
		XXXHOSTIDXXX, XXXADMINXXX, XXXVERSIONXXX, XXXBANDWIDTHXXX,
		XXXXSESSIONLIMITRXXX, XXXXSESSIONLIMITWXXX,
		XXXXCHANNELLIMITRXXX, XXXXCHANNELLIMITWXXX,
		XXXXDELAYCOMMDXXX, XXXXDELAYRETRYXXX,
		XXXLOCALXXX, XXXNETWORKXXX,
		XXXERRORMESGXXX, 
		XXXLANGXXX, XXXCURLANGENXXX, XXXCURLANGFRXXX, XXXCURSYSLANGENXXX, XXXCURSYSLANGFRXXX;
	}

	public static final int LIMITROW = 48; // better if it can
											// be divided by 4

	private String readFileHeader(String filename) {
		String value;
		try {
			value = WaarpStringUtils.readFileException(filename);
		} catch (InvalidArgumentException e) {
			logger.error("Error while trying to open: " + filename, e);
			return "";
		} catch (FileTransferException e) {
			logger.error("Error while trying to read: " + filename, e);
			return "";
		}
		StringBuilder builder = new StringBuilder(value);
		WaarpStringUtils.replace(builder, REPLACEMENT.XXXLOCALXXX.toString(),
				Integer.toString(
						Configuration.configuration.getLocalTransaction().
								getNumberLocalChannel()) + " " + Thread.activeCount());
		WaarpStringUtils.replace(builder, REPLACEMENT.XXXNETWORKXXX.toString(),
				Integer.toString(
						Configuration.configuration.getLocalTransaction().
								getNumberLocalChannel()));
		WaarpStringUtils.replace(builder, REPLACEMENT.XXXHOSTIDXXX.toString(),
				Configuration.configuration.HOST_ID);
		if (authentHttp.isAuthenticated()) {
			WaarpStringUtils.replace(builder, REPLACEMENT.XXXADMINXXX.toString(),
					Messages.getString("HttpSslHandler.1")); //$NON-NLS-1$
		} else {
			WaarpStringUtils.replace(builder, REPLACEMENT.XXXADMINXXX.toString(),
					Messages.getString("HttpSslHandler.0")); //$NON-NLS-1$
		}
		TrafficCounter trafficCounter =
				Configuration.configuration.getGlobalTrafficShapingHandler().getTrafficCounter();
		WaarpStringUtils.replace(builder, REPLACEMENT.XXXBANDWIDTHXXX.toString(),
				Messages.getString("HttpSslHandler.IN") + (trafficCounter.getLastReadThroughput() >> 17) + //$NON-NLS-1$
						Messages.getString("HttpSslHandler.OUT") + //$NON-NLS-1$
						(trafficCounter.getLastWriteThroughput() >> 17) + "Mbits");
		WaarpStringUtils.replace(builder, REPLACEMENT.XXXLANGXXX.toString(), lang);
		return builder.toString();
	}

	private String getTrimValue(String varname) {
		String value = params.get(varname).get(0).trim();
		if (value.isEmpty()) {
			value = null;
		}
		return value;
	}

	private String index() {
		String index = REQUEST.index.readFileUnique(this);
		StringBuilder builder = new StringBuilder(index);
		WaarpStringUtils.replaceAll(builder, REPLACEMENT.XXXHOSTIDXXX.toString(),
				Configuration.configuration.HOST_ID);
		WaarpStringUtils.replaceAll(builder, REPLACEMENT.XXXADMINXXX.toString(),
				"Administrator Connected");
		WaarpStringUtils.replace(builder, REPLACEMENT.XXXVERSIONXXX.toString(),
				Version.ID);
		return builder.toString();
	}

	private String error(String mesg) {
		String index = REQUEST.error.readFileUnique(this);
		return index.replaceAll(REPLACEMENT.XXXERRORMESGXXX.toString(),
				mesg);
	}

	private String Logon() {
		return REQUEST.Logon.readFileUnique(this);
	}

	/**
	 * Applied current lang to system page
	 * @param builder
	 */
	private void langHandle(StringBuilder builder) {
		// i18n: add here any new languages
		WaarpStringUtils.replace(builder, REPLACEMENT.XXXCURLANGENXXX.name(), lang.equalsIgnoreCase("en") ? "checked" : "");
		WaarpStringUtils.replace(builder, REPLACEMENT.XXXCURLANGFRXXX.name(), lang.equalsIgnoreCase("fr") ? "checked" : "");
		WaarpStringUtils.replace(builder, REPLACEMENT.XXXCURSYSLANGENXXX.name(), Messages.slocale.equalsIgnoreCase("en") ? "checked" : "");
		WaarpStringUtils.replace(builder, REPLACEMENT.XXXCURSYSLANGFRXXX.name(), Messages.slocale.equalsIgnoreCase("fr") ? "checked" : "");
	}

	private String System() {
		getParams();
		if (params == null) {
			String system = REQUEST.System.readFileUnique(this);
			StringBuilder builder = new StringBuilder(system);
			WaarpStringUtils.replace(builder, REPLACEMENT.XXXXSESSIONLIMITWXXX.toString(),
					Long.toString(Configuration.configuration.serverChannelWriteLimit));
			WaarpStringUtils.replace(builder, REPLACEMENT.XXXXSESSIONLIMITRXXX.toString(),
					Long.toString(Configuration.configuration.serverChannelReadLimit));
			WaarpStringUtils.replace(builder, REPLACEMENT.XXXXDELAYCOMMDXXX.toString(),
					Long.toString(Configuration.configuration.delayCommander));
			WaarpStringUtils.replace(builder, REPLACEMENT.XXXXDELAYRETRYXXX.toString(),
					Long.toString(Configuration.configuration.delayRetry));
			WaarpStringUtils.replace(builder, REPLACEMENT.XXXXCHANNELLIMITWXXX.toString(),
					Long.toString(Configuration.configuration.serverGlobalWriteLimit));
			WaarpStringUtils.replace(builder, REPLACEMENT.XXXXCHANNELLIMITRXXX.toString(),
					Long.toString(Configuration.configuration.serverGlobalReadLimit));
			langHandle(builder);
			return builder.toString();
		}
		String extraInformation = null;
		if (params.containsKey("ACTION")) {
			List<String> action = params.get("ACTION");
			for (String act : action) {
				if (act.equalsIgnoreCase("Language")) {
					lang = getTrimValue("change");
					String sys = getTrimValue("changesys");
					Messages.init(new Locale(sys));
					extraInformation = Messages.getString("HttpSslHandler.LangIs")+"Web: "+lang+" OpenR66: "+Messages.slocale; //$NON-NLS-1$
				} else if (act.equalsIgnoreCase("Disconnect")) {
					String logon = Logon();
					newSession = true;
					clearSession();
					forceClose = true;
					return logon;
				} else if (act.equalsIgnoreCase("Shutdown")) {
					String error;
					if (Configuration.configuration.shutdownConfiguration.serviceFuture != null) {
						error = error(Messages.getString("HttpSslHandler.38")); //$NON-NLS-1$
					} else {
						error = error(Messages.getString("HttpSslHandler.37")); //$NON-NLS-1$
					}
					R66ShutdownHook.setRestart(false);
					newSession = true;
					clearSession();
					forceClose = true;
					shutdown = true;
					return error;
				} else if (act.equalsIgnoreCase("Restart")) {
					String error;
					if (Configuration.configuration.shutdownConfiguration.serviceFuture != null) {
						error = error(Messages.getString("HttpSslHandler.38")); //$NON-NLS-1$
					} else {
						error = error(Messages.getString("HttpSslHandler.39")+(Configuration.configuration.TIMEOUTCON*2/1000)+Messages.getString("HttpSslHandler.40")); //$NON-NLS-1$ //$NON-NLS-2$
					}
					error = error.replace("XXXRELOADHTTPXXX", "HTTP-EQUIV=\"refresh\" CONTENT=\""+(Configuration.configuration.TIMEOUTCON*2/1000)+"\"");
					R66ShutdownHook.setRestart(true);
					newSession = true;
					clearSession();
					forceClose = true;
					shutdown = true;
					return error;
				} else if (act.equalsIgnoreCase("Validate")) {
					String bsessionr = getTrimValue("BSESSR");
					long lsessionr = Configuration.configuration.serverChannelReadLimit;
					long lglobalr;
					long lsessionw;
					long lglobalw;
					try {
						if (bsessionr != null) {
							lsessionr = (Long.parseLong(bsessionr) / 10) * 10;
						}
						String bglobalr = getTrimValue("BGLOBR");
						lglobalr = Configuration.configuration.serverGlobalReadLimit;
						if (bglobalr != null) {
							lglobalr = (Long.parseLong(bglobalr) / 10) * 10;
						}
						String bsessionw = getTrimValue("BSESSW");
						lsessionw = Configuration.configuration.serverChannelWriteLimit;
						if (bsessionw != null) {
							lsessionw = (Long.parseLong(bsessionw) / 10) * 10;
						}
						String bglobalw = getTrimValue("BGLOBW");
						lglobalw = Configuration.configuration.serverGlobalWriteLimit;
						if (bglobalw != null) {
							lglobalw = (Long.parseLong(bglobalw) / 10) * 10;
						}
						Configuration.configuration.changeNetworkLimit(
								lglobalw, lglobalr, lsessionw, lsessionr,
								Configuration.configuration.delayLimit);
						String dcomm = getTrimValue("DCOM");
						if (dcomm != null) {
							Configuration.configuration.delayCommander = Long.parseLong(dcomm);
							if (Configuration.configuration.delayCommander <= 100) {
								Configuration.configuration.delayCommander = 100;
							}
							Configuration.configuration.reloadCommanderDelay();
						}
						String dret = getTrimValue("DRET");
						if (dret != null) {
							Configuration.configuration.delayRetry = Long.parseLong(dret);
							if (Configuration.configuration.delayRetry <= 1000) {
								Configuration.configuration.delayRetry = 1000;
							}
						}
						extraInformation = Messages.getString("HttpSslHandler.41"); //$NON-NLS-1$
					} catch (NumberFormatException e) {
						extraInformation = Messages.getString("HttpSslHandler.42"); //$NON-NLS-1$
					}
				}
			}
		}
		String system = REQUEST.System.readFileUnique(this);
		StringBuilder builder = new StringBuilder(system);
		WaarpStringUtils.replace(builder, REPLACEMENT.XXXXSESSIONLIMITWXXX.toString(),
				Long.toString(Configuration.configuration.serverChannelWriteLimit));
		WaarpStringUtils.replace(builder, REPLACEMENT.XXXXSESSIONLIMITRXXX.toString(),
				Long.toString(Configuration.configuration.serverChannelReadLimit));
		WaarpStringUtils.replace(builder, REPLACEMENT.XXXXDELAYCOMMDXXX.toString(),
				Long.toString(Configuration.configuration.delayCommander));
		WaarpStringUtils.replace(builder, REPLACEMENT.XXXXDELAYRETRYXXX.toString(),
				Long.toString(Configuration.configuration.delayRetry));
		WaarpStringUtils.replace(builder, REPLACEMENT.XXXXCHANNELLIMITWXXX.toString(),
				Long.toString(Configuration.configuration.serverGlobalWriteLimit));
		WaarpStringUtils.replace(builder, REPLACEMENT.XXXXCHANNELLIMITRXXX.toString(),
				Long.toString(Configuration.configuration.serverGlobalReadLimit));
		langHandle(builder);
		if (extraInformation != null) {
			builder.append(extraInformation);
		}
		return builder.toString();
	}

	private void getParams() {
		if (request.getMethod() == HttpMethod.GET) {
			params = null;
		} else if (request.getMethod() == HttpMethod.POST) {
			ChannelBuffer content = request.getContent();
			if (content.readable()) {
				String param = content.toString(WaarpStringUtils.UTF8);
				QueryStringDecoder queryStringDecoder2 = new QueryStringDecoder("/?" + param);
				params = queryStringDecoder2.getParameters();
			} else {
				params = null;
			}
		}
	}

	private void clearSession() {
		if (admin != null) {
			R66Session lsession = sessions.remove(admin.getValue());
			admin = null;
			if (lsession != null) {
				lsession.setStatus(75);
				lsession.clear();
			}
		}
	}

	private void checkAuthent(MessageEvent e) {
		newSession = true;
		if (request.getMethod() == HttpMethod.GET) {
			String logon = Logon();
			responseContent.append(logon);
			clearSession();
			writeResponse(e.getChannel());
			return;
		} else if (request.getMethod() == HttpMethod.POST) {
			getParams();
			if (params == null) {
				String logon = Logon();
				responseContent.append(logon);
				clearSession();
				writeResponse(e.getChannel());
				return;
			}
		}
		boolean getMenu = false;
		if (params.containsKey("Logon")) {
			String name = null, password = null;
			List<String> values = null;
			if (!params.isEmpty()) {
				// get values
				if (params.containsKey("name")) {
					values = params.get("name");
					if (values != null) {
						name = values.get(0);
						if (name == null || name.isEmpty()) {
							getMenu = true;
						}
					}
				} else {
					getMenu = true;
				}
				// search the nb param
				if ((!getMenu) && params.containsKey("passwd")) {
					values = params.get("passwd");
					if (values != null) {
						password = values.get(0);
						if (password == null || password.isEmpty()) {
							getMenu = true;
						} else {
							getMenu = false;
						}
					} else {
						getMenu = true;
					}
				} else {
					getMenu = true;
				}
			} else {
				getMenu = true;
			}
			if (!getMenu) {
				logger.debug("Name=" + name + " vs "
						+ name.equals(Configuration.configuration.ADMINNAME) +
						" Passwd vs " + Arrays.equals(password.getBytes(WaarpStringUtils.UTF8),
								Configuration.configuration.getSERVERADMINKEY()));
				if (name.equals(Configuration.configuration.ADMINNAME) &&
						Arrays.equals(password.getBytes(WaarpStringUtils.UTF8),
								Configuration.configuration.getSERVERADMINKEY())) {
					authentHttp.getAuth().specialNoSessionAuth(true,
							Configuration.configuration.HOST_ID);
					authentHttp.setStatus(70);
				} else {
					getMenu = true;
				}
				if (!authentHttp.isAuthenticated()) {
					authentHttp.setStatus(71);
					logger.debug("Still not authenticated: {}", authentHttp);
					getMenu = true;
				}
			}
		} else {
			getMenu = true;
		}
		if (getMenu) {
			String logon = Logon();
			responseContent.append(logon);
			clearSession();
			writeResponse(e.getChannel());
		} else {
			String index = index();
			responseContent.append(index);
			clearSession();
			admin = new DefaultCookie(R66SESSION, Configuration.configuration.HOST_ID +
					Long.toHexString(random.nextLong()));
			sessions.put(admin.getValue(), this.authentHttp);
			authentHttp.setStatus(72);
			logger.debug("CreateSession: " + uriRequest + ":{}", admin);
			writeResponse(e.getChannel());
		}
	}

	@Override
	public void messageReceived(ChannelHandlerContext ctx, MessageEvent e) throws Exception {
		HttpRequest request = this.request = (HttpRequest) e.getMessage();
		queryStringDecoder = new QueryStringDecoder(request.getUri());
		uriRequest = queryStringDecoder.getPath();
		logger.debug("Msg: " + uriRequest);
		if (uriRequest.contains("gre/") || uriRequest.contains("img/") ||
				uriRequest.contains("res/") || uriRequest.contains("favicon.ico")) {
			HttpWriteCacheEnable.writeFile(request,
					e.getChannel(), Configuration.configuration.httpBasePath + uriRequest,
					R66SESSION);
			return;
		}
		checkSession(e.getChannel());
		if (!authentHttp.isAuthenticated()) {
			logger.debug("Not Authent: " + uriRequest + ":{}", authentHttp);
			checkAuthent(e);
			return;
		}
		String find = uriRequest;
		if (uriRequest.charAt(0) == '/') {
			find = uriRequest.substring(1);
		}
		find = find.substring(0, find.indexOf("."));
		REQUEST req = REQUEST.index;
		try {
			req = REQUEST.valueOf(find);
		} catch (IllegalArgumentException e1) {
			req = REQUEST.index;
			logger.debug("NotFound: " + find + ":" + uriRequest);
		}
		switch (req) {
			case index:
				responseContent.append(index());
				break;
			case Logon:
				responseContent.append(index());
				break;
			case System:
				responseContent.append(System());
				break;
			default:
				responseContent.append(index());
				break;
		}
		writeResponse(e.getChannel());
	}

	private void checkSession(Channel channel) {
		String cookieString = request.getHeader(HttpHeaders.Names.COOKIE);
		if (cookieString != null) {
			CookieDecoder cookieDecoder = new CookieDecoder();
			Set<Cookie> cookies = cookieDecoder.decode(cookieString);
			if (!cookies.isEmpty()) {
				for (Cookie elt : cookies) {
					if (elt.getName().equalsIgnoreCase(R66SESSION)) {
						logger.debug("Found session: "+elt);
						admin = elt;
						R66Session session = sessions.get(admin.getValue());
						if (session != null) {
							authentHttp = session;
							authentHttp.setStatus(73);
						} else {
							admin = null;
							continue;
						}
					} else if (elt.getName().equalsIgnoreCase(I18NEXT)) {
						logger.debug("Found i18next: "+elt);
						lang = elt.getValue();
					}
				}
			}
		}
		if (admin == null) {
			logger.debug("NoSession: " + uriRequest + ":{}", admin);
		}
	}

	private void handleCookies(HttpResponse response) {
		String cookieString = request.getHeader(HttpHeaders.Names.COOKIE);
		boolean i18nextFound = false;
		if (cookieString != null) {
			CookieDecoder cookieDecoder = new CookieDecoder();
			Set<Cookie> cookies = cookieDecoder.decode(cookieString);
			if (!cookies.isEmpty()) {
				// Reset the sessions if necessary.
				CookieEncoder cookieEncoder = new CookieEncoder(true);
				boolean findSession = false;
				for (Cookie cookie : cookies) {
					if (cookie.getName().equalsIgnoreCase(R66SESSION)) {
						if (newSession) {
							findSession = false;
						} else {
							findSession = true;
							cookieEncoder.addCookie(cookie);
							response.addHeader(HttpHeaders.Names.SET_COOKIE, cookieEncoder.encode());
							cookieEncoder = new CookieEncoder(true);
						}
					} else if (cookie.getName().equalsIgnoreCase(I18NEXT)) {
						i18nextFound = true;
						cookie.setValue(lang);
						cookieEncoder.addCookie(cookie);
						response.addHeader(HttpHeaders.Names.SET_COOKIE, cookieEncoder.encode());
						cookieEncoder = new CookieEncoder(true);
					} else {
						cookieEncoder.addCookie(cookie);
						response.addHeader(HttpHeaders.Names.SET_COOKIE, cookieEncoder.encode());
						cookieEncoder = new CookieEncoder(true);
					}
				}
				if (! i18nextFound) {
					Cookie cookie = new DefaultCookie(I18NEXT, lang);
					cookieEncoder.addCookie(cookie);
					response.addHeader(HttpHeaders.Names.SET_COOKIE, cookieEncoder.encode());
					cookieEncoder = new CookieEncoder(true);
				}
				newSession = false;
				if (!findSession) {
					if (admin != null) {
						cookieEncoder.addCookie(admin);
						response.addHeader(HttpHeaders.Names.SET_COOKIE, cookieEncoder.encode());
						logger.debug("AddSession: " + uriRequest + ":{}", admin);
					}
				}
			}
		} else {
			CookieEncoder cookieEncoder = new CookieEncoder(true);
			Cookie cookie = new DefaultCookie(I18NEXT, lang);
			cookieEncoder.addCookie(cookie);
			response.addHeader(HttpHeaders.Names.SET_COOKIE, cookieEncoder.encode());
			if (admin != null) {
				cookieEncoder = new CookieEncoder(true);
				cookieEncoder.addCookie(admin);
				logger.debug("AddSession: " + uriRequest + ":{}", admin);
				response.addHeader(HttpHeaders.Names.SET_COOKIE, cookieEncoder.encode());
			}
		}
	}

	/**
	 * Write the response
	 * 
	 * @param e
	 */
	private void writeResponse(Channel channel) {
		// Convert the response content to a ChannelBuffer.
		ChannelBuffer buf = ChannelBuffers.copiedBuffer(responseContent.toString(),
				WaarpStringUtils.UTF8);
		responseContent.setLength(0);

		// Decide whether to close the connection or not.
		boolean keepAlive = HttpHeaders.isKeepAlive(request);
		boolean close = HttpHeaders.Values.CLOSE.equalsIgnoreCase(request
				.getHeader(HttpHeaders.Names.CONNECTION)) ||
				(!keepAlive) || forceClose;

		// Build the response object.
		HttpResponse response = new DefaultHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK);
		response.setContent(buf);
		response.setHeader(HttpHeaders.Names.CONTENT_TYPE, "text/html");
		if (keepAlive) {
			response.setHeader(HttpHeaders.Names.CONNECTION, HttpHeaders.Values.KEEP_ALIVE);
		}
		if (!close) {
			// There's no need to add 'Content-Length' header
			// if this is the last response.
			response.setHeader(HttpHeaders.Names.CONTENT_LENGTH,
					String.valueOf(buf.readableBytes()));
		}

		handleCookies(response);

		// Write the response.
		ChannelFuture future = channel.write(response);
		// Close the connection after the write operation is done if necessary.
		if (close) {
			future.addListener(WaarpSslUtility.SSLCLOSE);
		}
		if (shutdown) {
			ChannelUtils.startShutdown();
		}
	}

	/**
	 * Send an error and close
	 * 
	 * @param ctx
	 * @param status
	 */
	private void sendError(ChannelHandlerContext ctx, HttpResponseStatus status) {
		HttpResponse response = new DefaultHttpResponse(
				HttpVersion.HTTP_1_1, status);
		response.setHeader(
				HttpHeaders.Names.CONTENT_TYPE, "text/html");
		responseContent.setLength(0);
		responseContent.append(error(status.toString()));
		response.setContent(ChannelBuffers.copiedBuffer(responseContent.toString(),
				WaarpStringUtils.UTF8));
		clearSession();
		// Close the connection as soon as the error message is sent.
		ctx.getChannel().write(response).addListener(WaarpSslUtility.SSLCLOSE);
	}

	@Override
	public void exceptionCaught(ChannelHandlerContext ctx, ExceptionEvent e)
			throws Exception {
		OpenR66Exception exception = OpenR66ExceptionTrappedFactory
				.getExceptionFromTrappedException(e.getChannel(), e);
		if (exception != null) {
			if (!(exception instanceof OpenR66ProtocolBusinessNoWriteBackException)) {
				if (e.getCause() instanceof IOException) {
					// Nothing to do
					return;
				}
				logger.warn("Exception in HttpSslHandler {}", exception.getMessage());
			}
			if (e.getChannel().isConnected()) {
				sendError(ctx, HttpResponseStatus.BAD_REQUEST);
			}
		} else {
			// Nothing to do
			return;
		}
	}

	/*
	 * (non-Javadoc)
	 * @see
	 * org.jboss.netty.channel.SimpleChannelUpstreamHandler#channelOpen(org.jboss.netty.channel.
	 * ChannelHandlerContext, org.jboss.netty.channel.ChannelStateEvent)
	 */
	@Override
	public void channelOpen(ChannelHandlerContext ctx, ChannelStateEvent e)
			throws Exception {
		Channel channel = e.getChannel();
		Configuration.configuration.getHttpChannelGroup().add(channel);
		super.channelOpen(ctx, e);
	}
}
