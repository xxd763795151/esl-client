package io.github.xxd763795151.esl.connection;

/**
 * Notified, in order and on a worker thread, whenever a connection changes state.
 */
@FunctionalInterface
public interface ConnectionStateListener {

    /**
     * @param from  previous state
     * @param to    new state
     * @param cause what triggered the change when it was a failure (connection lost, auth failed ...),
     *              otherwise {@code null}
     */
    void onStateChanged(ConnectionState from, ConnectionState to, Throwable cause);
}
