package io.github.xxd763795151.esl.internal.netty;

import io.github.xxd763795151.esl.connection.TransportType;
import io.netty.channel.IoHandlerFactory;
import io.netty.channel.nio.NioIoHandler;
import io.netty.channel.socket.ServerSocketChannel;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Picks the Netty transport. Native transports are optional dependencies: their classes are only touched
 * inside the nested holder classes, so a missing jar surfaces as a caught {@link LinkageError}.
 */
public final class Transports {

    private static final Logger log = LoggerFactory.getLogger(Transports.class);

    private Transports() {
    }

    public record Transport(String name, IoHandlerFactory ioHandlerFactory,
                            Class<? extends SocketChannel> socketChannel,
                            Class<? extends ServerSocketChannel> serverSocketChannel) {
    }

    public static Transport select(TransportType type) {
        Transport transport = switch (type) {
            case NIO -> nio();
            case EPOLL -> require(type, attempt(Epoll::create));
            case KQUEUE -> require(type, attempt(KQueue::create));
            case IO_URING -> require(type, attempt(IoUring::create));
            case AUTO -> {
                Transport found = attempt(Epoll::create);
                if (found == null) {
                    found = attempt(KQueue::create);
                }
                yield found != null ? found : nio();
            }
        };
        log.debug("Using {} transport", transport.name());
        return transport;
    }

    private static Transport nio() {
        return new Transport("nio", NioIoHandler.newFactory(), NioSocketChannel.class, NioServerSocketChannel.class);
    }

    private static Transport require(TransportType type, Transport transport) {
        if (transport == null) {
            throw new IllegalStateException(type + " transport requested but not available on this platform/class path");
        }
        return transport;
    }

    private static Transport attempt(java.util.function.Supplier<Transport> factory) {
        try {
            return factory.get();
        } catch (LinkageError | RuntimeException e) {
            return null;
        }
    }

    private static final class Epoll {
        static Transport create() {
            if (!io.netty.channel.epoll.Epoll.isAvailable()) {
                return null;
            }
            return new Transport("epoll", io.netty.channel.epoll.EpollIoHandler.newFactory(),
                    io.netty.channel.epoll.EpollSocketChannel.class,
                    io.netty.channel.epoll.EpollServerSocketChannel.class);
        }
    }

    private static final class KQueue {
        static Transport create() {
            if (!io.netty.channel.kqueue.KQueue.isAvailable()) {
                return null;
            }
            return new Transport("kqueue", io.netty.channel.kqueue.KQueueIoHandler.newFactory(),
                    io.netty.channel.kqueue.KQueueSocketChannel.class,
                    io.netty.channel.kqueue.KQueueServerSocketChannel.class);
        }
    }

    private static final class IoUring {
        static Transport create() {
            if (!io.netty.channel.uring.IoUring.isAvailable()) {
                return null;
            }
            return new Transport("io_uring", io.netty.channel.uring.IoUringIoHandler.newFactory(),
                    io.netty.channel.uring.IoUringSocketChannel.class,
                    io.netty.channel.uring.IoUringServerSocketChannel.class);
        }
    }
}
