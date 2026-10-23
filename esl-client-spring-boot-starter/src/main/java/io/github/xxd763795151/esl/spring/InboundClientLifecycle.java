package io.github.xxd763795151.esl.spring;

import io.github.xxd763795151.esl.inbound.InboundClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Connects the inbound client on startup and closes it gracefully on shutdown. Runs in an early phase, so the
 * client is ready before the web server accepts requests and stays available until the web server has
 * finished its own graceful shutdown.
 */
public class InboundClientLifecycle implements SmartLifecycle {

    /** Starts before and stops after the web server (which uses {@code DEFAULT_PHASE - 1024/2048}). */
    public static final int PHASE = SmartLifecycle.DEFAULT_PHASE - 4096;

    private static final Logger log = LoggerFactory.getLogger(InboundClientLifecycle.class);

    private final InboundClient client;
    private final Duration startupWait;
    private final boolean failFast;
    private final Duration shutdownTimeout;
    private volatile boolean running;

    public InboundClientLifecycle(InboundClient client, Duration startupWait, boolean failFast,
                                  Duration shutdownTimeout) {
        this.client = client;
        this.startupWait = startupWait;
        this.failFast = failFast;
        this.shutdownTimeout = shutdownTimeout;
    }

    @Override
    public void start() {
        running = true;
        CompletableFuture<Void> connected = client.connect();
        if (startupWait.isZero() && !failFast) {
            return;
        }
        try {
            if (failFast) {
                connected.get();
            } else {
                connected.get(startupWait.toMillis(), TimeUnit.MILLISECONDS);
            }
        } catch (TimeoutException e) {
            log.warn("{} not connected after {}, continuing startup while it keeps trying", client, startupWait);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (ExecutionException e) {
            if (failFast) {
                throw new IllegalStateException("Cannot connect " + client, e.getCause());
            }
            log.error("{} could not connect: {}", client, e.getCause().toString());
        }
    }

    @Override
    public void stop(Runnable callback) {
        client.closeGracefully(shutdownTimeout).whenComplete((ignored, error) -> {
            running = false;
            callback.run();
        });
    }

    @Override
    public void stop() {
        client.close();
        running = false;
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    @Override
    public int getPhase() {
        return PHASE;
    }
}
