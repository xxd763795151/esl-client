package io.github.xxd763795151.esl.test;

import io.netty.bootstrap.Bootstrap;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.MultiThreadIoEventLoopGroup;
import io.netty.channel.nio.NioIoHandler;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.util.concurrent.DefaultThreadFactory;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * A scriptable stand-in for FreeSWITCH's mod_event_socket, for tests.
 * <ul>
 *   <li>Inbound mode: {@link #start()} listens on a random port, greets every connection with
 *       {@code auth/request} and answers commands like FreeSWITCH would.</li>
 *   <li>Outbound mode: {@link #dialOutbound(int, Map)} connects to an outbound server like FreeSWITCH's
 *       {@code socket} application does.</li>
 * </ul>
 */
public final class FakeFreeSwitch implements AutoCloseable {

    private final EventLoopGroup group = new MultiThreadIoEventLoopGroup(2,
            new DefaultThreadFactory("fake-fs", true), NioIoHandler.newFactory());
    private final List<FakePeer> peers = new CopyOnWriteArrayList<>();
    private final BlockingQueue<FakePeer> connected = new LinkedBlockingQueue<>();
    private volatile Channel serverChannel;
    private volatile String password = "ClueCon";
    private volatile String rudeRejection;
    private volatile boolean greet = true;
    private volatile FakePeer.CommandHandler defaultHandler;

    private FakeFreeSwitch() {
    }

    /** Starts listening for inbound clients on 127.0.0.1 with a random port. */
    public static FakeFreeSwitch start() {
        FakeFreeSwitch fake = new FakeFreeSwitch();
        fake.listen();
        return fake;
    }

    /** Creates an instance that only dials outbound servers. */
    public static FakeFreeSwitch dialer() {
        return new FakeFreeSwitch();
    }

    private void listen() {
        serverChannel = new ServerBootstrap()
                .group(group)
                .channel(NioServerSocketChannel.class)
                .childHandler(new ChannelInitializer<Channel>() {
                    @Override
                    protected void initChannel(Channel channel) {
                        FakePeer peer = new FakePeer(FakeFreeSwitch.this, Map.of());
                        channel.pipeline().addLast(new FakeCommandDecoder(), peer);
                    }
                })
                .bind("127.0.0.1", 0).syncUninterruptibly().channel();
    }

    public int port() {
        return ((InetSocketAddress) serverChannel.localAddress()).getPort();
    }

    public FakeFreeSwitch password(String password) {
        this.password = password;
        return this;
    }

    String password() {
        return password;
    }

    /** Answer new connections with {@code text/rude-rejection} and close them. */
    public FakeFreeSwitch rudeRejection(String reason) {
        this.rudeRejection = reason;
        return this;
    }

    /** Whether new inbound connections get an {@code auth/request} (default true). */
    public FakeFreeSwitch greet(boolean greet) {
        this.greet = greet;
        return this;
    }

    /** Handler applied to peers without their own handler. */
    public FakeFreeSwitch handler(FakePeer.CommandHandler handler) {
        this.defaultHandler = handler;
        return this;
    }

    FakePeer.CommandHandler defaultHandler() {
        return defaultHandler;
    }

    void peerConnected(FakePeer peer) {
        peers.add(peer);
        if (serverChannel != null && peer.channel().parent() == serverChannel) {
            String rejection = rudeRejection;
            if (rejection != null) {
                peer.channel().writeAndFlush(Unpooled.copiedBuffer("Content-Type: text/rude-rejection\nContent-Length: "
                                + rejection.getBytes(StandardCharsets.UTF_8).length + "\n\n" + rejection,
                        StandardCharsets.UTF_8)).addListener(ChannelFutureListener.CLOSE);
            } else if (greet) {
                peer.send("Content-Type: auth/request\n\n");
            }
        }
        connected.add(peer);
    }

    /** Next connection (inbound or dialled), waiting up to {@code timeout}. */
    public FakePeer awaitPeer(Duration timeout) {
        try {
            FakePeer peer = connected.poll(timeout.toMillis(), TimeUnit.MILLISECONDS);
            if (peer == null) {
                throw new AssertionError("No connection within " + timeout);
            }
            return peer;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError(e);
        }
    }

    public List<FakePeer> peers() {
        return List.copyOf(peers);
    }

    /** Connects to an outbound server like the {@code socket} dialplan application. */
    public FakePeer dialOutbound(int port, Map<String, String> channelData) {
        FakePeer peer = new FakePeer(this, channelData);
        new Bootstrap()
                .group(group)
                .channel(NioSocketChannel.class)
                .handler(new ChannelInitializer<Channel>() {
                    @Override
                    protected void initChannel(Channel channel) {
                        channel.pipeline().addLast(new FakeCommandDecoder(), peer);
                    }
                })
                .connect("127.0.0.1", port).syncUninterruptibly();
        return peer;
    }

    /** Stops listening (existing connections stay open). */
    public void stopListening() {
        Channel channel = serverChannel;
        if (channel != null) {
            channel.close().syncUninterruptibly();
        }
    }

    /** Closes every open connection. */
    public void dropAllConnections() {
        peers.forEach(FakePeer::close);
    }

    @Override
    public void close() {
        stopListening();
        group.shutdownGracefully(0, 2, TimeUnit.SECONDS).syncUninterruptibly();
    }
}
