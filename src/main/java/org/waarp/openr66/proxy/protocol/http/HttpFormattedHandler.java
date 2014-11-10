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
package org.waarp.openr66.proxy.protocol.http;

import java.io.IOException;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelStateEvent;
import io.netty.channel.ExceptionEvent;
import io.netty.channel.MessageEvent;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.group.ChannelGroup;
import io.netty.handler.codec.http.Cookie;
import io.netty.handler.codec.http.CookieDecoder;
import io.netty.handler.codec.http.CookieEncoder;
import io.netty.handler.codec.http.DefaultCookie;
import io.netty.handler.codec.http.DefaultHttpResponse;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponse;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.codec.http.QueryStringDecoder;
import io.netty.handler.traffic.TrafficCounter;
import org.waarp.common.exception.FileTransferException;
import org.waarp.common.exception.InvalidArgumentException;
import org.waarp.common.logging.WaarpLogger;
import org.waarp.common.logging.WaarpLoggerFactory;
import org.waarp.common.utility.WaarpStringUtils;
import org.waarp.gateway.kernel.http.HttpWriteCacheEnable;
import org.waarp.openr66.context.R66Session;
import org.waarp.openr66.context.filesystem.R66Dir;
import org.waarp.openr66.protocol.configuration.Messages;
import org.waarp.openr66.protocol.exception.OpenR66Exception;
import org.waarp.openr66.protocol.exception.OpenR66ExceptionTrappedFactory;
import org.waarp.openr66.protocol.exception.OpenR66ProtocolBusinessNoWriteBackException;
import org.waarp.openr66.proxy.configuration.Configuration;

/**
 * Handler for HTTP information support
 * 
 * @author Frederic Bregier
 * 
 */
public class HttpFormattedHandler extends SimpleChannelInboundHandler {
    /**
     * Internal Logger
     */
    private static final WaarpLogger logger = WaarpLoggerFactory
            .getLogger(HttpFormattedHandler.class);

    private static enum REQUEST {
        index("index.html"),
        error("monitoring_header.html", "monitoring_end.html"),
        statusxml("");

        private String header;
        private String end;

        /**
         * Constructor for a unique file
         * 
         * @param uniquefile
         */
        private REQUEST(String uniquefile) {
            this.header = uniquefile;
            this.end = uniquefile;
        }

        /**
         * @param header
         * @param end
         */
        private REQUEST(String header, String end) {
            this.header = header;
            this.end = end;
        }

        /**
         * Reader for a unique file
         * 
         * @return the content of the unique file
         */
        public String readFileUnique(HttpFormattedHandler handler) {
            return handler.readFileHeader(Configuration.configuration.httpBasePath + "monitor/"
                    + this.header);
        }

        public String readHeader(HttpFormattedHandler handler) {
            return handler.readFileHeader(Configuration.configuration.httpBasePath + "monitor/"
                    + this.header);
        }

        public String readEnd() {
            return WaarpStringUtils.readFile(Configuration.configuration.httpBasePath + "monitor/"
                    + this.end);
        }
    }

    private static enum REPLACEMENT {
        XXXHOSTIDXXX, XXXLOCACTIVEXXX, XXXNETACTIVEXXX, XXXBANDWIDTHXXX, XXXDATEXXX, XXXLANGXXX;
    }

    public static final int LIMITROW = 60; // better
                                           // if
                                           // it
                                           // can
                                           // be
                                           // divided
                                           // by
                                           // 4
    private static final String I18NEXT = "i18next";

    public final R66Session authentHttp = new R66Session();

    public static final ConcurrentHashMap<String, R66Dir> usedDir = new ConcurrentHashMap<String, R66Dir>();

    private HttpRequest request;

    private final StringBuilder responseContent = new StringBuilder();

    private HttpResponseStatus status;

    private String uriRequest;

    private String lang = Messages.slocale;

    private boolean isCurrentRequestXml = false;

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

        WaarpStringUtils.replace(builder, REPLACEMENT.XXXDATEXXX.toString(),
                (new Date()).toString());
        WaarpStringUtils.replace(builder, REPLACEMENT.XXXLOCACTIVEXXX.toString(),
                Integer.toString(
                        Configuration.configuration.getLocalTransaction().
                                getNumberLocalChannel()));
        WaarpStringUtils.replace(builder, REPLACEMENT.XXXNETACTIVEXXX.toString(),
                Integer.toString(
                        Configuration.configuration.getLocalTransaction().
                                getNumberLocalChannel()));
        WaarpStringUtils.replace(builder, REPLACEMENT.XXXHOSTIDXXX.toString(),
                Configuration.configuration.HOST_ID);
        TrafficCounter trafficCounter =
                Configuration.configuration.getGlobalTrafficShapingHandler().getTrafficCounter();
        WaarpStringUtils.replace(builder, REPLACEMENT.XXXBANDWIDTHXXX.toString(),
                "IN:" + (trafficCounter.getLastReadThroughput() / 131072) +
                        "Mbits&nbsp;&nbsp;OUT:" +
                        (trafficCounter.getLastWriteThroughput() / 131072) + "Mbits");
        WaarpStringUtils.replace(builder, REPLACEMENT.XXXLANGXXX.toString(), lang);
        return builder.toString();
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, MessageEvent e)
            throws Exception {
        isCurrentRequestXml = false;
        status = HttpResponseStatus.OK;
        HttpRequest request = this.request = (HttpRequest) e.getMessage();
        QueryStringDecoder queryStringDecoder = new QueryStringDecoder(request
                .getUri());
        uriRequest = queryStringDecoder.getPath();
        logger.debug("Msg: " + uriRequest);
        if (uriRequest.contains("gre/") || uriRequest.contains("img/") ||
                uriRequest.contains("res/") || uriRequest.contains("favicon.ico")) {
            HttpWriteCacheEnable.writeFile(request,
                    e.channel(), Configuration.configuration.httpBasePath + uriRequest,
                    "XYZR66NOSESSION");
            return;
        }
        char cval = 'z';
        long nb = LIMITROW;
        // check the URI
        if (uriRequest.equalsIgnoreCase("/statusxml")) {
            cval = '5';
            nb = 0; // since it could be the default or setup by request
            isCurrentRequestXml = true;
        }
        if (request.getMethod() == HttpMethod.GET) {
            Map<String, List<String>> params = queryStringDecoder.getParameters();
            String value = null;
            try {
                value = params.get("setLng").get(0).trim();
            } catch (NullPointerException e1) {
                value = null;
            }
            if (value != null && !value.isEmpty()) {
                lang = value;
            }
        }
        boolean getMenu = (cval == 'z');
        boolean extraBoolean = false;
        if (getMenu) {
            responseContent.append(REQUEST.index.readFileUnique(this));
        } else {
            // Use value 0=Active 1=Error 2=Done 3=All
            switch (cval) {
                case '5':
                    statusxml(ctx, nb, extraBoolean);
                    break;
                default:
                    responseContent.append(REQUEST.index.readFileUnique(this));
            }
        }
        writeResponse(e);
    }

    /**
     * print only status
     * 
     * @param ctx
     * @param nb
     */
    private void statusxml(ChannelHandlerContext ctx, long nb, boolean detail) {
        Configuration.configuration.monitoring.run(nb, detail);
        responseContent.append(Configuration.configuration.monitoring.exportXml(detail));
    }

    /**
     * Write the response
     * 
     * @param e
     */
    private void writeResponse(MessageEvent e) {
        // Convert the response content to a ByteBuf.
        ByteBuf buf = Unpooled.copiedBuffer(responseContent
                .toString(), WaarpStringUtils.UTF8);
        responseContent.setLength(0);
        // Decide whether to close the connection or not.
        boolean keepAlive = HttpHeaders.isKeepAlive(request);
        boolean close = HttpHeaders.Values.CLOSE.equalsIgnoreCase(request
                .headers().get(HttpHeaders.Names.CONNECTION)) ||
                (!keepAlive);

        // Build the response object.
        HttpResponse response = new DefaultHttpResponse(HttpVersion.HTTP_1_1,
                status);
        response.setContent(buf);
        if (isCurrentRequestXml) {
            response.headers().set(HttpHeaders.Names.CONTENT_TYPE, "text/xml");
        } else {
            response.headers().set(HttpHeaders.Names.CONTENT_TYPE, "text/html");
        }
        if (keepAlive) {
            response.headers().set(HttpHeaders.Names.CONNECTION,
                    HttpHeaders.Values.KEEP_ALIVE);
        }
        if (!close) {
            // There's no need to add 'Content-Length' header
            // if this is the last response.
            response.headers().set(HttpHeaders.Names.CONTENT_LENGTH, String
                    .valueOf(buf.readableBytes()));
        }

        String cookieString = request.headers().get(HttpHeaders.Names.COOKIE);
        if (cookieString != null) {
            CookieDecoder cookieDecoder = new CookieDecoder();
            Set<Cookie> cookies = cookieDecoder.decode(cookieString);
            boolean i18nextFound = false;
            if (!cookies.isEmpty()) {
                // Reset the cookies if necessary.
                CookieEncoder cookieEncoder = new CookieEncoder(true);
                for (Cookie cookie : cookies) {
                    if (cookie.getName().equalsIgnoreCase(I18NEXT)) {
                        i18nextFound = true;
                        cookie.setValue(lang);
                        cookieEncoder.addCookie(cookie);
                        response.headers().add(HttpHeaders.Names.SET_COOKIE, cookieEncoder.encode());
                        cookieEncoder = new CookieEncoder(true);
                    } else {
                        cookieEncoder.addCookie(cookie);
                        response.headers().add(HttpHeaders.Names.SET_COOKIE, cookieEncoder.encode());
                        cookieEncoder = new CookieEncoder(true);
                    }
                }
                if (!i18nextFound) {
                    Cookie cookie = new DefaultCookie(I18NEXT, lang);
                    cookieEncoder.addCookie(cookie);
                    response.headers().add(HttpHeaders.Names.SET_COOKIE, cookieEncoder.encode());
                }
            }
            if (!i18nextFound) {
                Cookie cookie = new DefaultCookie(I18NEXT, lang);
                CookieEncoder cookieEncoder = new CookieEncoder(true);
                cookieEncoder.addCookie(cookie);
                response.headers().add(HttpHeaders.Names.SET_COOKIE, cookieEncoder.encode());
            }
        }

        // Write the response.
        ChannelFuture future = e.channel().writeAndFlush(response);

        // Close the connection after the write operation is done if necessary.
        if (close) {
            future.addListener(ChannelFutureListener.CLOSE);
        }
    }

    /**
     * Send an error and close
     * 
     * @param ctx
     * @param status
     */
    private void sendError(ChannelHandlerContext ctx, HttpResponseStatus status) {
        HttpResponse response = new DefaultHttpResponse(HttpVersion.HTTP_1_1,
                status);
        response.headers().set(HttpHeaders.Names.CONTENT_TYPE, "text/html");
        responseContent.setLength(0);
        responseContent.append(REQUEST.error.readHeader(this)).append("OpenR66 Web Failure: ")
                .append(status.toString()).append(REQUEST.error.readEnd());
        response.setContent(Unpooled.copiedBuffer(responseContent
                .toString(), WaarpStringUtils.UTF8));
        // Close the connection as soon as the error message is sent.
        ctx.channel().writeAndFlush(response).addListener(
                ChannelFutureListener.CLOSE);
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause)
            throws Exception {
        OpenR66Exception exception = OpenR66ExceptionTrappedFactory
                .getExceptionFromTrappedException(e.channel(), e);
        if (exception != null) {
            if (!(exception instanceof OpenR66ProtocolBusinessNoWriteBackException)) {
                if (e.getCause() instanceof IOException) {
                    // Nothing to do
                    return;
                }
                logger.warn("Exception in HttpHandler {}", exception.getMessage());
            }
            if (e.channel().isActive()) {
                sendError(ctx, HttpResponseStatus.BAD_REQUEST);
            }
        } else {
            // Nothing to do
            return;
        }
    }

    @Override
    public void channelClosed(ChannelHandlerContext ctx, ChannelStateEvent e)
            throws Exception {
        logger.debug("Closed");
        super.channelClosed(ctx, e);
    }

    @Override
    public void channelConnected(ChannelHandlerContext ctx, ChannelStateEvent e)
            throws Exception {
        logger.debug("Connected");
        authentHttp.getAuth().specialNoSessionAuth(false, Configuration.configuration.HOST_ID);
        super.channelConnected(ctx, e);
        ChannelGroup group = Configuration.configuration.getHttpChannelGroup();
        if (group != null) {
            group.add(e.channel());
        }
    }
}
