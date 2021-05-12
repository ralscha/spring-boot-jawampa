/*
 * Copyright 2014 Matthias Einwag
 *
 * The jawampa authors license this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package ws.wamp.jawampa.transport.netty;

import java.net.URI;
import java.util.List;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

import ws.wamp.jawampa.ApplicationError;
import ws.wamp.jawampa.WampRouter;
import ws.wamp.jawampa.WampSerialization;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpServerCodec;
import io.netty.handler.codec.http.HttpUtil;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.handler.ssl.util.SelfSignedCertificate;
import io.netty.util.CharsetUtil;
import static io.netty.handler.codec.http.HttpHeaderNames.*;
import static io.netty.handler.codec.http.HttpMethod.*;
import static io.netty.handler.codec.http.HttpResponseStatus.*;
import static io.netty.handler.codec.http.HttpVersion.*;

/**
 * A simple default implementation for the websocket adapter for a WAMP router.<br>
 * It provides listening capabilities for a WAMP router on a given websocket address.
 */
public class SimpleWampWebsocketListener {
    
    enum State {
        Intialized,
        Started,
        Closed
    }
    
    State state = State.Intialized;
    
    final EventLoopGroup bossGroup;
    final EventLoopGroup clientGroup;
    final WampRouter router;
    
    final URI uri;
    SslContext sslCtx;
    List<WampSerialization> serializations;
    
    Channel channel;
    
    boolean started = false;
    
    public SimpleWampWebsocketListener(WampRouter router, URI uri, SslContext sslContext) throws ApplicationError {
        this(router, uri, sslContext, WampSerialization.defaultSerializations());
    }

    public SimpleWampWebsocketListener(WampRouter router, URI uri, SslContext sslContext,
                                       List<WampSerialization> serializations) throws ApplicationError {
        this.router = router;
        this.uri = uri;
        this.serializations = serializations;
        
        if (serializations == null || serializations.size() == 0 || serializations.contains(WampSerialization.Invalid))
            throw new ApplicationError(ApplicationError.INVALID_SERIALIZATIONS);

        this.bossGroup = new NioEventLoopGroup(1, new ThreadFactory(){
            @Override
            public Thread newThread(Runnable r){
                return new Thread(r, "WampRouterBossLoop");
            }
        });
        this.clientGroup = new NioEventLoopGroup(Runtime.getRuntime().availableProcessors(), new ThreadFactory(){
            private AtomicInteger counter = new AtomicInteger();
            @Override
            public Thread newThread(Runnable r){
                return new Thread(r, "WampRouterClientLoop-"+this.counter.incrementAndGet());
            }
        });

        // Copy the ssl context only when we really want ssl
        if (uri.getScheme().equalsIgnoreCase("wss")) {
            this.sslCtx = sslContext;
        }
    }

    public void start() {
        if (this.state != State.Intialized) return;
        
        try {
            // Initialize SSL when required
            if (this.uri.getScheme().equalsIgnoreCase("wss") && this.sslCtx == null) {
                // Use a self signed certificate when we got none provided through the constructor
                SelfSignedCertificate ssc = new SelfSignedCertificate();
                this.sslCtx =
                    SslContextBuilder
                    .forServer(ssc.certificate(), ssc.privateKey())
                    .build();
            }
            
            // Use well-known ports if not explicitly specified
            final int port;
            if (this.uri.getPort() == -1) {
                if (this.sslCtx != null) port = 443;
                else port = 80;
            } else port = this.uri.getPort();
        
            ServerBootstrap b = new ServerBootstrap();
            b.group(this.bossGroup, this.clientGroup)
             .channel(NioServerSocketChannel.class)
             .childHandler(new WebSocketServerInitializer(this.uri, this.sslCtx));
            
            this.channel = b.bind(this.uri.getHost(), port).sync().channel();
        } 
        catch(Exception e) {
            throw new RuntimeException(e);
        }
    }
    
    public void stop() {
        if (this.state == State.Closed) return;
        
        if (this.channel != null) {
            try {
                this.channel.close().sync();
            } catch (InterruptedException e) {
            }
            this.channel = null;
        }
        
        this.bossGroup.shutdownGracefully();
        this.clientGroup.shutdownGracefully();
        
        this.state = State.Closed;
    }
    
    private class WebSocketServerInitializer extends ChannelInitializer<SocketChannel> {
        private final URI uri;
        private final SslContext sslCtx;
        
        public WebSocketServerInitializer(URI uri, SslContext sslCtx) {
            this.uri = uri;
            this.sslCtx = sslCtx;
        }
        
        @Override
        public void initChannel(SocketChannel ch) throws Exception {
            ChannelPipeline pipeline = ch.pipeline();
            if (this.sslCtx != null) {
                pipeline.addLast(this.sslCtx.newHandler(ch.alloc()));
            }
            pipeline.addLast(new HttpServerCodec());
            pipeline.addLast(new HttpObjectAggregator(65536));
            pipeline.addLast(new WampServerWebsocketHandler(this.uri.getPath().length()==0 ? "/" : this.uri.getPath(), SimpleWampWebsocketListener.this.router,
                    SimpleWampWebsocketListener.this.serializations));
            pipeline.addLast(new WebSocketServerHandler(this.uri));
        }
    }
    
    /**
     * Handles handshakes and messages
     */
    public static class WebSocketServerHandler extends SimpleChannelInboundHandler<FullHttpRequest> {
        
        private final URI uri;
        
        WebSocketServerHandler(URI uri) {
            this.uri = uri;
        }
        
        @Override
        public void channelRead0(ChannelHandlerContext ctx, FullHttpRequest msg) {
            handleHttpRequest(ctx, msg);
        }
        
        private void handleHttpRequest(ChannelHandlerContext ctx, FullHttpRequest req) {
            // Handle a bad request.
            if (!req.decoderResult().isSuccess()) {
                sendHttpResponse(ctx, req, new DefaultFullHttpResponse(HTTP_1_1, BAD_REQUEST));
                return;
            }
            // Allow only GET methods.
            if (req.method() != GET) {
                sendHttpResponse(ctx, req, new DefaultFullHttpResponse(HTTP_1_1, FORBIDDEN));
                return;
            }
            // Send the demo page and favicon.ico
            if ("/".equals(req.uri())) {
                ByteBuf content = Unpooled.copiedBuffer(
                    "<html><head><title>Wamp Router</title></head><body>" +
                    "<h1>This server provides a wamp router on path " + 
                    this.uri.getPath() + "</h1>" +
                    "</body></html>"
                    , CharsetUtil.UTF_8);
                FullHttpResponse res = new DefaultFullHttpResponse(HTTP_1_1, OK, content);
                res.headers().set(CONTENT_TYPE, "text/html; charset=UTF-8");
                HttpUtil.setContentLength(res, content.readableBytes());
                sendHttpResponse(ctx, req, res);
                return;
            }
            if ("/favicon.ico".equals(req.uri())) {
                FullHttpResponse res = new DefaultFullHttpResponse(HTTP_1_1, NOT_FOUND);
                sendHttpResponse(ctx, req, res);
                return;
            }
            
            FullHttpResponse res = new DefaultFullHttpResponse(HTTP_1_1, NOT_FOUND);
            sendHttpResponse(ctx, req, res);
        }
        
        private static void sendHttpResponse(
            ChannelHandlerContext ctx, FullHttpRequest req, FullHttpResponse res) {
            // Generate an error page if response getStatus code is not OK (200).
            if (res.status().code() != 200) {
                ByteBuf buf = Unpooled.copiedBuffer(res.status().toString(), CharsetUtil.UTF_8);
                res.content().writeBytes(buf);
                buf.release();
                HttpUtil.setContentLength(res, res.content().readableBytes());
            }
            // Send the response and close the connection if necessary.
            ChannelFuture f = ctx.channel().writeAndFlush(res);
            if (!HttpUtil.isKeepAlive(req) || res.status().code() != 200) {
                f.addListener(ChannelFutureListener.CLOSE);
            }
        }
        
        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
            cause.printStackTrace();
            ctx.close();
        }
    }
}
