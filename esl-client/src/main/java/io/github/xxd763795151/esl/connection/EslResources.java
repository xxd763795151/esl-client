package io.github.xxd763795151.esl.connection;

import io.github.xxd763795151.esl.internal.netty.Transports;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.MultiThreadIoEventLoopGroup;
import io.netty.channel.socket.ServerSocketChannel;
import io.netty.channel.socket.SocketChannel;
import io.netty.util.concurrent.DefaultThreadFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Threads used by clients and servers: a Netty I/O event loop group and a worker executor for listeners and
 * future completions.
 * <p>
 * Each client/server creates and owns its own instance by default and releases it on close. To run many
 * clients on the same threads, create one instance, pass it to every builder, and close it yourself after the
 * clients. All threads are daemon threads, so a forgotten close never keeps the JVM alive.
 */
public final class EslResources implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(EslResources.class);

    private final EventLoopGroup ioGroup;
    private final Transports.Transport transport;
    private final Executor worker;
    private final ExecutorService ownedWorker;
    private final AtomicBoolean shutdown = new AtomicBoolean();
    private final CompletableFuture<Void> terminationFuture = new CompletableFuture<>();

    private EslResources(Builder builder) {
        this.transport = Transports.select(builder.transportType);
        ThreadFactory ioThreads = new DefaultThreadFactory(builder.threadNamePrefix + "-io", true);
        this.ioGroup = new MultiThreadIoEventLoopGroup(builder.ioThreads, ioThreads, transport.ioHandlerFactory());
        if (builder.worker != null) {
            this.worker = builder.worker;
            this.ownedWorker = null;
        } else {
            this.ownedWorker = builder.virtualThreads
                    ? virtualThreadExecutor(builder.threadNamePrefix + "-worker")
                    : cachedExecutor(builder.threadNamePrefix + "-worker");
            this.worker = ownedWorker;
        }
    }

    public static EslResources create() {
        return builder().build();
    }

    public static Builder builder() {
        return new Builder();
    }

    public EventLoopGroup ioGroup() {
        return ioGroup;
    }

    public Executor worker() {
        return worker;
    }

    public Class<? extends SocketChannel> socketChannelClass() {
        return transport.socketChannel();
    }

    public Class<? extends ServerSocketChannel> serverSocketChannelClass() {
        return transport.serverSocketChannel();
    }

    public String transportName() {
        return transport.name();
    }

    public boolean isShutdown() {
        return shutdown.get();
    }

    /**
     * Stops the I/O threads (closing any channel still open) and the owned worker pool, waiting for running
     * tasks up to {@code timeout}. A user supplied worker executor is left alone.
     */
    public CompletableFuture<Void> shutdownGracefully(Duration timeout) {
        if (!shutdown.compareAndSet(false, true)) {
            return terminationFuture;
        }
        long quietMillis = Math.min(100, Math.max(0, timeout.toMillis() / 4));
        CompletableFuture<Void> ioDone = new CompletableFuture<>();
        ioGroup.shutdownGracefully(quietMillis, Math.max(quietMillis, timeout.toMillis()), TimeUnit.MILLISECONDS)
                .addListener(future -> ioDone.complete(null));
        ioDone.thenRun(() -> {
            if (ownedWorker == null) {
                terminationFuture.complete(null);
                return;
            }
            ownedWorker.shutdown();
            // awaiting termination blocks, so do it on a short lived thread of its own
            Thread waiter = new Thread(() -> {
                try {
                    if (!ownedWorker.awaitTermination(Math.max(1, timeout.toMillis()), TimeUnit.MILLISECONDS)) {
                        log.warn("Worker threads still busy after {} ms, interrupting", timeout.toMillis());
                        ownedWorker.shutdownNow();
                    }
                } catch (InterruptedException e) {
                    ownedWorker.shutdownNow();
                    Thread.currentThread().interrupt();
                } finally {
                    terminationFuture.complete(null);
                }
            }, "esl-shutdown");
            waiter.setDaemon(true);
            waiter.start();
        });
        return terminationFuture;
    }

    /** {@link #shutdownGracefully(Duration)} with a 10 second timeout, waiting for completion. */
    @Override
    public void close() {
        Duration timeout = Duration.ofSeconds(10);
        try {
            shutdownGracefully(timeout).get(timeout.toMillis() + 1000, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            log.warn("Resources did not terminate cleanly", e);
        }
    }

    /**
     * Whether the current thread belongs to an owned worker pool. Waiting there for that same pool to terminate
     * would only end by timeout, so blocking close methods skip the wait.
     */
    public static boolean isWorkerThread() {
        return Thread.currentThread() instanceof WorkerThread;
    }

    private static final class WorkerThread extends Thread {
        WorkerThread(Runnable runnable, String name) {
            super(runnable, name);
        }
    }

    private static ExecutorService cachedExecutor(String prefix) {
        AtomicInteger counter = new AtomicInteger();
        ThreadFactory factory = runnable -> {
            Thread thread = new WorkerThread(runnable, prefix + '-' + counter.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
        // grows with concurrent blocking work (e.g. one thread per outbound call script) and shrinks when idle
        return new ThreadPoolExecutor(0, Integer.MAX_VALUE, 60, TimeUnit.SECONDS, new SynchronousQueue<>(), factory);
    }

    private static ExecutorService virtualThreadExecutor(String prefix) {
        try {
            Object builder = Thread.class.getMethod("ofVirtual").invoke(null);
            Class<?> builderType = Class.forName("java.lang.Thread$Builder");
            builder = builderType.getMethod("name", String.class, long.class).invoke(builder, prefix + '-', 1L);
            ThreadFactory factory = (ThreadFactory) builderType.getMethod("factory").invoke(builder);
            return (ExecutorService) MethodHandles.publicLookup()
                    .findStatic(java.util.concurrent.Executors.class, "newThreadPerTaskExecutor",
                            MethodType.methodType(ExecutorService.class, ThreadFactory.class))
                    .invoke(factory);
        } catch (Throwable e) {
            throw new IllegalStateException("Virtual threads require Java 21 or later", e);
        }
    }

    public static final class Builder {
        private int ioThreads = Math.min(4, Runtime.getRuntime().availableProcessors());
        private TransportType transportType = TransportType.AUTO;
        private Executor worker;
        private boolean virtualThreads;
        private String threadNamePrefix = "esl";

        private Builder() {
        }

        /** Number of I/O threads; a handful serves thousands of connections. */
        public Builder ioThreads(int ioThreads) {
            if (ioThreads < 1) {
                throw new IllegalArgumentException("ioThreads must be >= 1");
            }
            this.ioThreads = ioThreads;
            return this;
        }

        public Builder transport(TransportType transportType) {
            this.transportType = Objects.requireNonNull(transportType, "transportType");
            return this;
        }

        /** Uses the given executor for listeners and completions. It is not shut down by these resources. */
        public Builder workerExecutor(Executor worker) {
            this.worker = Objects.requireNonNull(worker, "worker");
            return this;
        }

        /** Runs listeners and completions on virtual threads (Java 21+). */
        public Builder virtualThreads(boolean virtualThreads) {
            this.virtualThreads = virtualThreads;
            return this;
        }

        public Builder threadNamePrefix(String threadNamePrefix) {
            this.threadNamePrefix = Objects.requireNonNull(threadNamePrefix, "threadNamePrefix");
            return this;
        }

        public EslResources build() {
            return new EslResources(this);
        }
    }
}
