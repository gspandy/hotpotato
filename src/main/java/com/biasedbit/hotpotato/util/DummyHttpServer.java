/*
 * Copyright 2010 Bruno de Carvalho
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.biasedbit.hotpotato.util;

import org.jboss.netty.bootstrap.ServerBootstrap;
import org.jboss.netty.buffer.ChannelBuffer;
import org.jboss.netty.buffer.ChannelBuffers;
import org.jboss.netty.channel.Channel;
import org.jboss.netty.channel.ChannelFuture;
import org.jboss.netty.channel.ChannelFutureListener;
import org.jboss.netty.channel.ChannelHandlerContext;
import org.jboss.netty.channel.ChannelPipeline;
import org.jboss.netty.channel.ChannelPipelineFactory;
import org.jboss.netty.channel.ChannelStateEvent;
import org.jboss.netty.channel.Channels;
import org.jboss.netty.channel.ExceptionEvent;
import org.jboss.netty.channel.MessageEvent;
import org.jboss.netty.channel.SimpleChannelUpstreamHandler;
import org.jboss.netty.channel.group.DefaultChannelGroup;
import org.jboss.netty.channel.socket.nio.NioServerSocketChannelFactory;
import org.jboss.netty.channel.socket.oio.OioServerSocketChannelFactory;
import org.jboss.netty.handler.codec.http.DefaultHttpResponse;
import org.jboss.netty.handler.codec.http.HttpChunkAggregator;
import org.jboss.netty.handler.codec.http.HttpHeaders;
import org.jboss.netty.handler.codec.http.HttpRequest;
import org.jboss.netty.handler.codec.http.HttpRequestDecoder;
import org.jboss.netty.handler.codec.http.HttpResponse;
import org.jboss.netty.handler.codec.http.HttpResponseEncoder;
import org.jboss.netty.handler.codec.http.HttpResponseStatus;
import org.jboss.netty.handler.ssl.SslHandler;
import org.jboss.netty.util.CharsetUtil;

import java.io.BufferedInputStream;
import java.io.FileInputStream;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.security.KeyStore;
import java.security.SecureRandom;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.TrustManagerFactory;

/**
 * A simple HttpServer with configurable error introduction.
 *
 * @author <a href="http://bruno.biasedbit.com/">Bruno de Carvalho</a>
 */
public class DummyHttpServer {

    // configuration defaults -----------------------------------------------------------------------------------------

    private static final boolean USE_SSL = false;
    private static final float FAILURE_PROBABILITY = 0.0f;
    private static final long RESPONSE_LATENCY = 0;
    private static final boolean USE_OLD_IO = false;
    // Taken from http://www.w3schools.com/XML/xml_examples.asp
    private static final String CONTENT =
            "<breakfast_menu> \n" +
            "\t<food> \n" +
            "\t\t<name>Belgian Waffles</name> \n" +
            "\t\t<price>$5.95</price> \n" +
            "\t\t<description>two of our famous Belgian Waffles with plenty of real maple syrup</description> \n" +
            "\t\t<calories>650</calories> \n" +
            "\t</food> \n" +
            "\t<food> \n" +
            "\t\t<name>Strawberry Belgian Waffles</name> \n" +
            "\t\t<price>$7.95</price> \n" +
            "\t\t<description>light Belgian waffles covered with strawberries and whipped cream</description> \n" +
            "\t\t<calories>900</calories> \n" +
            "\t</food> \n" +
            "\t<food> \n" +
            "\t\t<name>Berry-Berry Belgian Waffles</name> \n" +
            "\t\t<price>$8.95</price> \n" +
            "\t\t<description>light Belgian waffles covered with an assortment of fresh berries and " +
            "whipped cream</description> \n" +
            "\t\t<calories>900</calories> \n" +
            "\t</food> \n" +
            "\t<food> \n" +
            "\t\t<name>French Toast</name> \n" +
            "\t\t<price>$4.50</price> \n" +
            "\t\t<description>thick slices made from our homemade sourdough bread</description> \n" +
            "\t\t<calories>600</calories> \n" +
            "\t</food> \n" +
            "\t<food> \n" +
            "\t\t<name>Homestyle Breakfast</name> \n" +
            "\t\t<price>$6.95</price> \n" +
            "\t\t<description>two eggs, bacon or sausage, toast, and our ever-popular hash browns</description> \n" +
            "\t\t<calories>950</calories> \n" +
            "\t</food> \n" +
            "</breakfast_menu> ";

    // configuration --------------------------------------------------------------------------------------------------

    private final String host;
    private final int port;
    private boolean useSsl;
    private float failureProbability;
    private long responseLatency;
    private boolean useOldIo;
    private String content;

    // internal vars --------------------------------------------------------------------------------------------------

    private ServerBootstrap bootstrap;
    private DefaultChannelGroup channelGroup;
    private final AtomicInteger errors;
    private boolean running;

    // constructors ---------------------------------------------------------------------------------------------------

    public DummyHttpServer(String host, int port) {
        this.host = host;
        this.port = port;
        this.errors = new AtomicInteger();

        this.useSsl = USE_SSL;
        this.failureProbability = FAILURE_PROBABILITY;
        this.responseLatency = RESPONSE_LATENCY;
        this.content = CONTENT;
        this.useOldIo = USE_OLD_IO;
    }

    public DummyHttpServer(int port) {
        this(null, port);
    }

    // public methods -------------------------------------------------------------------------------------------------

    public boolean init() {
        if (this.useOldIo) {
            this.bootstrap = new ServerBootstrap(new OioServerSocketChannelFactory(Executors.newCachedThreadPool(),
                                                                                   Executors.newCachedThreadPool()));
        } else {
            this.bootstrap = new ServerBootstrap(new NioServerSocketChannelFactory(Executors.newCachedThreadPool(),
                                                                                   Executors.newCachedThreadPool()));
        }

        this.bootstrap.setOption("child.tcpNoDelay", true);
        this.bootstrap.setPipelineFactory(new ChannelPipelineFactory() {
            @Override
            public ChannelPipeline getPipeline() throws Exception {
                ChannelPipeline pipeline = Channels.pipeline();

                if (useSsl) {
                    pipeline.addLast("ssl", createSelfSignedSslHandler());
                }

                pipeline.addLast("encoder", new HttpResponseEncoder());
                pipeline.addLast("decoder", new HttpRequestDecoder());
                pipeline.addLast("aggregator", new HttpChunkAggregator(5242880)); // 5MB
                pipeline.addLast("handler", new RequestHandler());
                return pipeline;
            }
        });
        this.channelGroup = new DefaultChannelGroup("hotpotato-http-server-" + Integer.toHexString(this.hashCode()));

        SocketAddress bindAddress;
        if (this.host != null) {
            bindAddress = new InetSocketAddress(this.host, this.port);
        } else {
            bindAddress = new InetSocketAddress(this.port);
        }
        Channel serverChannel = this.bootstrap.bind(bindAddress);
        this.channelGroup.add(serverChannel);

        return (this.running = serverChannel.isBound());
    }

    public void terminate() {
        if (!this.running) {
            return;
        }

        this.running = false;
        this.channelGroup.close().awaitUninterruptibly();
        this.bootstrap.releaseExternalResources();
    }

    // getters & setters ----------------------------------------------------------------------------------------------

    public boolean isUseSsl() {
        return useSsl;
    }

    public void setUseSsl(boolean useSsl) {
        this.useSsl = useSsl;
    }

    public float getFailureProbability() {
        return failureProbability;
    }

    public void setFailureProbability(float failureProbability) {
        if (failureProbability < 0) {
            this.failureProbability = 0;
        } else if (failureProbability > 1.0) {
            this.failureProbability = 1;
        } else {
            this.failureProbability = failureProbability;
        }
    }

    public long getResponseLatency() {
        return responseLatency;
    }

    public void setResponseLatency(long responseLatency) {
        this.responseLatency = responseLatency;
    }

    public boolean isUseOldIo() {
        return useOldIo;
    }

    public void setUseOldIo(boolean useOldIo) {
        this.useOldIo = useOldIo;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }

    public boolean isRunning() {
        return running;
    }

    // private classes ------------------------------------------------------------------------------------------------

    private final class RequestHandler extends SimpleChannelUpstreamHandler {

        private final ChannelBuffer contentBuffer = ChannelBuffers.copiedBuffer(content, CharsetUtil.UTF_8);

        // SimpleChannelUpstreamHandler -------------------------------------------------------------------------------

        @Override
        public void channelOpen(ChannelHandlerContext ctx, ChannelStateEvent e) throws Exception {
            channelGroup.add(e.getChannel());
        }

        @Override
        public void messageReceived(ChannelHandlerContext ctx, MessageEvent e) throws Exception {
            if (Math.random() <= failureProbability) {
                errors.incrementAndGet();
                e.getChannel().close();
                return;
            }

            if (responseLatency > 0) {
                try { Thread.sleep(responseLatency); } catch (InterruptedException ignored) { }
            }
            HttpRequest request = (HttpRequest) e.getMessage();
            System.err.println(request);
            if (request.getContent().readableBytes() > 0) {
                System.err.println("-------------");
                System.err.println("Body has " + request.getContent().readableBytes() + " readable bytes.");
                System.err.println("--- BODY START ----------");
                System.err.println(request.getContent().toString(CharsetUtil.UTF_8));
                System.err.println("--- BODY END ------------\n");
            }

            // Build the response object.
            HttpResponse response = new DefaultHttpResponse(request.getProtocolVersion(), HttpResponseStatus.ACCEPTED);
            response.setContent(contentBuffer);
            response.setHeader(HttpHeaders.Names.CONTENT_TYPE, "text/xml; charset=UTF-8");

            boolean keepAlive = HttpHeaders.isKeepAlive(request);
            if (keepAlive) {
                response.setHeader(HttpHeaders.Names.CONTENT_LENGTH, contentBuffer.readableBytes());
            }

            ChannelFuture f = e.getChannel().write(response);
            // Write the response & close the connection after the write operation.
            if (!keepAlive) {
                f.addListener(ChannelFutureListener.CLOSE);
            }
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, ExceptionEvent e) throws Exception {
            //System.err.println("Exception caught on HTTP connection from " + e.getChannel().getRemoteAddress() +
            //                   "; closing channel.");
            if (e.getChannel().isConnected()) {
                e.getChannel().close();
            }
        }
    }

    // main -----------------------------------------------------------------------------------------------------------

    public static void main(String[] args) {
        String host = null;
        int port = 80;
        float failureProbability = 0.0f;
        boolean useOio = false;
        if (args.length >= 1) {
            host = args[0];
        }
        if (args.length >= 2) {
            port = Integer.parseInt(args[1]);
        }
        if (args.length >= 3) {
            failureProbability = Float.parseFloat(args[2]);
        }
        if (args.length == 4) {
            useOio = ("useOio".equals(args[3]));
        }
        final DummyHttpServer server = new DummyHttpServer(host, port);
        server.setFailureProbability(failureProbability);
        server.setUseOldIo(useOio);
        //server.setResponseLatency(50L);
        if (!server.init()) {
            System.err.println("Failed to bind server to " + (host == null ? '*' : host) + ":" + port +
                               (useOio ? " (Oio)" : " (Nio)") + " (FP: " + failureProbability + ")");
        } else {
            System.out.println("Server bound to " + (host == null ? '*' : host) + ":" + port +
                               (useOio ? " (Oio)" : " (Nio)") + " (FP: " + failureProbability + ")");
        }

        Runtime.getRuntime().addShutdownHook(new Thread() {
            @Override
            public void run() {
                server.terminate();
            }
        });
    }

    public static SslHandler createSelfSignedSslHandler() throws Exception
    {
        String algorithm = "SunX509";
        String password = "password";

        KeyStore keyStore = KeyStore.getInstance("JKS");
        InputStream keyStoreAsStream = null;

        try
        {
            keyStoreAsStream = new BufferedInputStream(
                new FileInputStream("src/main/resources/dummyserver/selfsigned.jks"));

            keyStore.load(keyStoreAsStream, password.toCharArray());
        }
        finally
        {
            if (keyStoreAsStream != null)
            {
                try { keyStoreAsStream.close(); } catch (Exception e) {}
            }
        }

        KeyManagerFactory keyManagerFactory = KeyManagerFactory.getInstance(algorithm);
        TrustManagerFactory trustManagerFactory = TrustManagerFactory.getInstance(algorithm);

        keyManagerFactory.init(keyStore, password.toCharArray());
        trustManagerFactory.init(keyStore);

        SSLContext context = SSLContext.getInstance("TLS");
        context.init(keyManagerFactory.getKeyManagers(), trustManagerFactory.getTrustManagers(), new SecureRandom());

        SSLEngine engine = context.createSSLEngine();
        engine.setUseClientMode(false);

        return new SslHandler(engine, true);
    }
}
