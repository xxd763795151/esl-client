package io.github.xxd763795151.esl.exception;

import java.time.Duration;

/**
 * No reply (or completion event) arrived within the configured timeout.
 */
public class CommandTimeoutException extends EslException {

    private final Duration timeout;

    public CommandTimeoutException(String what, Duration timeout) {
        super(what + " timed out after " + timeout.toMillis() + " ms");
        this.timeout = timeout;
    }

    public Duration timeout() {
        return timeout;
    }
}
