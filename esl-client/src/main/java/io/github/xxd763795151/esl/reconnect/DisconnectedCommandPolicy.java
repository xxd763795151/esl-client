package io.github.xxd763795151.esl.reconnect;

/**
 * What happens to commands issued while an inbound client is (re)connecting.
 */
public enum DisconnectedCommandPolicy {

    /** Fail immediately with {@link io.github.xxd763795151.esl.exception.ConnectionClosedException}. */
    FAIL_FAST,

    /**
     * Hold commands (bounded, each still subject to the command timeout) and send them once the connection is
     * ready again.
     */
    QUEUE_UNTIL_READY
}
