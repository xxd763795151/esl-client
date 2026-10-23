package io.github.xxd763795151.esl.spring;

import io.github.xxd763795151.esl.outbound.OutboundServer;
import org.springframework.context.SmartLifecycle;

import java.time.Duration;
import java.util.concurrent.ExecutionException;

/**
 * Binds the outbound server with the web server phase (after the inbound client) and shuts it down gracefully,
 * letting active calls finish within the configured timeout.
 */
public class OutboundServerLifecycle implements SmartLifecycle {

    public static final int PHASE = SmartLifecycle.DEFAULT_PHASE - 1024;

    private final OutboundServer server;
    private final Duration shutdownTimeout;
    private volatile boolean running;

    public OutboundServerLifecycle(OutboundServer server, Duration shutdownTimeout) {
        this.server = server;
        this.shutdownTimeout = shutdownTimeout;
    }

    @Override
    public void start() {
        try {
            server.start().get();
            running = true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while starting " + server, e);
        } catch (ExecutionException e) {
            throw new IllegalStateException("Cannot start " + server, e.getCause());
        }
    }

    @Override
    public void stop(Runnable callback) {
        server.shutdownGracefully(shutdownTimeout).whenComplete((ignored, error) -> {
            running = false;
            callback.run();
        });
    }

    @Override
    public void stop() {
        server.close();
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
