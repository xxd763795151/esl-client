package io.github.xxd763795151.esl.connection;

/**
 * Lifecycle of an ESL connection.
 *
 * <pre>
 * NEW -&gt; CONNECTING -&gt; AUTHENTICATING -&gt; READY -&gt; CLOSING -&gt; CLOSED
 *                                          |  ^
 *                                          v  |
 *                                      RECONNECTING  (inbound, when a reconnect policy allows it)
 * </pre>
 */
public enum ConnectionState {
    NEW,
    CONNECTING,
    AUTHENTICATING,
    READY,
    RECONNECTING,
    CLOSING,
    CLOSED;

    public boolean isTerminal() {
        return this == CLOSING || this == CLOSED;
    }
}
