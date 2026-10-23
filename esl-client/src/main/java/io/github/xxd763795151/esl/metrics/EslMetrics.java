package io.github.xxd763795151.esl.metrics;

import io.github.xxd763795151.esl.connection.ConnectionState;

import java.util.function.IntSupplier;

/**
 * Metrics SPI. Every method has a no-op default so implementations only override what they record.
 * Implementations must be thread-safe and cheap: most methods are called on I/O threads.
 *
 * @see MicrometerEslMetrics
 */
public interface EslMetrics {

    String GAUGE_PENDING_COMMANDS = "pending.commands";
    String GAUGE_PENDING_JOBS = "pending.jobs";
    String GAUGE_PENDING_EXECUTES = "pending.executes";
    String GAUGE_DISPATCH_BACKLOG = "dispatch.backlog";
    String GAUGE_ACTIVE_SESSIONS = "sessions.active";
    String GAUGE_CONNECTED = "connected";

    EslMetrics NOOP = new EslMetrics() {
    };

    default void commandCompleted(String command, long durationNanos, CommandOutcome outcome) {
    }

    default void eventReceived(String eventName) {
    }

    default void eventDropped(String eventName) {
    }

    default void listenerFailed() {
    }

    default void stateChanged(ConnectionState from, ConnectionState to) {
    }

    default void reconnectAttempt() {
    }

    default void sessionOpened() {
    }

    default void sessionClosed() {
    }

    /**
     * Registers a gauge. Called once per gauge when a client or server is created.
     *
     * @param name   one of the {@code GAUGE_*} constants
     * @param source value supplier, read whenever the metric is scraped
     */
    default void gauge(String name, IntSupplier source) {
    }

    /** Releases registered meters when the owning client or server closes. */
    default void close() {
    }
}
