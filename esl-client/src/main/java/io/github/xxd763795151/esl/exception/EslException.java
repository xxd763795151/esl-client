package io.github.xxd763795151.esl.exception;

/**
 * Base class of all exceptions raised by the ESL client. Unchecked, so it travels through
 * {@link java.util.concurrent.CompletableFuture} chains unchanged.
 */
public class EslException extends RuntimeException {

    public EslException(String message) {
        super(message);
    }

    public EslException(String message, Throwable cause) {
        super(message, cause);
    }
}
