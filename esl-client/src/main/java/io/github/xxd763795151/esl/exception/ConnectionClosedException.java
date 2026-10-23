package io.github.xxd763795151.esl.exception;

/**
 * The connection is not (or no longer) usable: it was closed, lost, or never established.
 */
public class ConnectionClosedException extends EslException {

    public ConnectionClosedException(String message) {
        super(message);
    }

    public ConnectionClosedException(String message, Throwable cause) {
        super(message, cause);
    }
}
