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

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.group.ChannelGroup;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaderValues;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpUtil;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.codec.http.QueryStringDecoder;
import io.netty.handler.codec.http.cookie.Cookie;
import io.netty.handler.codec.http.cookie.DefaultCookie;
import io.netty.handler.codec.http.cookie.ServerCookieDecoder;
import io.netty.handler.codec.http.cookie.ServerCookieEncoder;
import io.netty.handler.traffic.TrafficCounter;

import org.waarp.common.exception.FileTransferException;
import org.waarp.common.exception.InvalidArgumentException;
import org.waarp.common.logging.WaarpLogger;
import org.waarp.common.logging.WaarpLoggerFactory;
import org.waarp.common.utility.WaarpStringUtils;
import org.waarp.gateway.kernel.http.HttpWriteCacheEnable;
import org.waarp.openr66.context.R66Session;
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
public class HttpFormattedHandler extends SimpleChannelInboundHandler<FullHttpRequest> {
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

    public static final int LIMITROW = 60; // better if it can be divided by 4
    private static final String I18NEXT = "i18next";

    public final R66Session authentHttp = new R66Session();

    private FullHttpRequest request;

    private final StringBuilder responseContent = new StringBuilder();

    private HttpResponseStatus status;

    private String uriRequest;

    private String lang = Messages.slocale;

    private boolean isCurrentRequestXml = false;
    private boolean isCurrentRequestJson = false;

    private Map<String, List<String>> params = null;

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
                Configuration.configuration.getGlobalTrafficShapingHandler().trafficCounter();
        WaarpStringUtils.replace(builder, REPLACEMENT.XXXBANDWIDTHXXX.toString(),
                "IN:" + (trafficCounter.lastReadThroughput() / 131072) +
                        "Mbits&nbsp;&nbsp;OUT:" +
                        (trafficCounter.lastWriteThroughput() / 131072) + "Mbits");
        WaarpStringUtils.replace(builder, REPLACEMENT.XXXLANGXXX.toString(), lang);
        return builder.toString();
    }

    private String getTrimValue(String varname) {
        String value = null;
        try {
            value = params.get(varname).get(0).trim();
        } catch (NullPointerException e) {
            return null;
        }
        if (value.isEmpty()) {
            value = null;
        }
        return value;
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, FullHttpRequest msg) throws Exception {
        isCurrentRequestXml = false;
        isCurrentRequestJson = false;
        status = HttpResponseStatus.OK;
        FullHttpRequest request = this.request = msg;
        QueryStringDecoder queryStringDecoder = new QueryStringDecoder(request.uri());
        uriRequest = queryStringDecoder.path();
        logger.debug("Msg: " + uriRequest);
        if (uriRequest.contains("gre/") || uriRequest.contains("img/") ||
                uriRequest.contains("res/") || uriRequest.contains("favicon.ico")) {
            HttpWriteCacheEnable.writeFile(request,
                    ctx, Configuration.configuration.httpBasePath + uriRequest,
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
        } else if (uriRequest.equalsIgnoreCase("/statusjson")) {
            cval = '7';
            nb = 0; // since it could be the default or setup by request
            isCurrentRequestJson = true;
        }
        if (request.method() == HttpMethod.GET) {
            params = queryStringDecoder.parameters();
        }
        boolean getMenu = (cval == 'z');
        boolean extraBoolean = false;
        if (!params.isEmpty()) {
            String langarg = getTrimValue("setLng");
            if (langarg != null && !langarg.isEmpty()) {
                lang = langarg;
            }
        }
        if (getMenu) {
            responseContent.append(REQUEST.index.readFileUnique(this));
        } else {
            // Use value 0=Active 1=Error 2=Done 3=All
            switch (cval) {
                case '5':
                    statusxml(ctx, nb, extraBoolean);
                    break;
                case '7':
                    statusjson(ctx, nb, extraBoolean);
                    break;
                default:
                    responseContent.append(REQUEST.index.readFileUnique(this));
            }
        }
        writeResponse(ctx);
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

    private void statusjson(ChannelHandlerContext ctx, long nb, boolean detail) {
        Configuration.configuration.monitoring.run(nb, detail);
        responseContent.append(Configuration.configuration.monitoring.exportJson(detail));
    }

    /**
     * Write the response
     * 
     */
    private void writeResponse(ChannelHandlerContext ctx) {
        // Convert the response content to a ByteBuf.
        ByteBuf buf = Unpooled.copiedBuffer(responseContent.toString(), WaarpStringUtils.UTF8);
        responseContent.setLength(0);
        // Decide whether to close the connection or not.
        boolean keepAlive = HttpUtil.isKeepAlive(request);
        boolean close = HttpHeaderValues.CLOSE.contentEqualsIgnoreCase(request
                .headers().get(HttpHeaderNames.CONNECTION)) ||
                (!keepAlive);

        // Build the response object.
        FullHttpResponse response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, status, buf);
        response.headers().add(HttpHeaderNames.CONTENT_LENGTH, response.content().readableBytes());
        if (isCurrentRequestXml) {
            response.headers().set(HttpHeaderNames.CONTENT_TYPE, "text/xml");
        } else if (isCurrentRequestJson) {
            response.headers().set(HttpHeaderNames.CONTENT_TYPE, "application/json");
        } else {
            response.headers().set(HttpHeaderNames.CONTENT_TYPE, "text/html");
        }
        if (keepAlive) {
            response.headers().set(HttpHeaderNames.CONNECTION,
                    HttpHeaderValues.KEEP_ALIVE);
        }
        if (!close) {
            // There's no need to add 'Content-Length' header
            // if this is the last response.
            response.headers().set(HttpHeaderNames.CONTENT_LENGTH, String.valueOf(buf.readableBytes()));
        }

        String cookieString = request.headers().get(HttpHeaderNames.COOKIE);
        if (cookieString != null) {
            Set<Cookie> cookies = ServerCookieDecoder.LAX.decode(cookieString);
            boolean i18nextFound = false;
            if (!cookies.isEmpty()) {
                // Reset the cookies if necessary.
                for (Cookie cookie : cookies) {
                    if (cookie.name().equalsIgnoreCase(I18NEXT)) {
                        i18nextFound = true;
                        cookie.setValue(lang);
                        response.headers().add(HttpHeaderNames.SET_COOKIE, ServerCookieEncoder.LAX.encode(cookie));
                    } else {
                        response.headers().add(HttpHeaderNames.SET_COOKIE, ServerCookieEncoder.LAX.encode(cookie));
                    }
                }
                if (!i18nextFound) {
                    Cookie cookie = new DefaultCookie(I18NEXT, lang);
                    response.headers().add(HttpHeaderNames.SET_COOKIE, ServerCookieEncoder.LAX.encode(cookie));
                }
            }
            if (!i18nextFound) {
                Cookie cookie = new DefaultCookie(I18NEXT, lang);
                response.headers().add(HttpHeaderNames.SET_COOKIE, ServerCookieEncoder.LAX.encode(cookie));
            }
        }

        // Write the response.
        ChannelFuture future = ctx.writeAndFlush(response);
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
        responseContent.setLength(0);
        responseContent.append(REQUEST.error.readHeader(this)).append("OpenR66 Web Failure: ")
                .append(status.toString()).append(REQUEST.error.readEnd());
        ByteBuf buf = Unpooled.copiedBuffer(responseContent.toString(), WaarpStringUtils.UTF8);
        FullHttpResponse response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, status, buf);
        response.headers().add(HttpHeaderNames.CONTENT_LENGTH, response.content().readableBytes());
        response.headers().set(HttpHeaderNames.CONTENT_TYPE, "text/html");
        responseContent.setLength(0);
        // Close the connection as soon as the error message is sent.
        ctx.writeAndFlush(response).addListener(ChannelFutureListener.CLOSE);
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause)
            throws Exception {
        OpenR66Exception exception = OpenR66ExceptionTrappedFactory
                .getExceptionFromTrappedException(ctx.channel(), cause);
        if (exception != null) {
            if (!(exception instanceof OpenR66ProtocolBusinessNoWriteBackException)) {
                if (cause instanceof IOException) {
                    // Nothing to do
                    return;
                }
                logger.warn("Exception in HttpHandler {}", exception.getMessage());
            }
            if (ctx.channel().isActive()) {
                sendError(ctx, HttpResponseStatus.BAD_REQUEST);
            }
        } else {
            // Nothing to do
            return;
        }
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) throws Exception {
        super.channelInactive(ctx);
        logger.debug("Closed");
    }

    @Override
    public void channelActive(ChannelHandlerContext ctx) throws Exception {
        logger.debug("Connected");
        authentHttp.getAuth().specialNoSessionAuth(false, Configuration.configuration.HOST_ID);
        super.channelActive(ctx);
        ChannelGroup group = Configuration.configuration.getHttpChannelGroup();
        if (group != null) {
            group.add(ctx.channel());
        }
    }
}
