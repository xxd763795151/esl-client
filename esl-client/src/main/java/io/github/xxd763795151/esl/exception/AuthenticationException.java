package io.github.xxd763795151.esl.exception;

/**
 * FreeSWITCH rejected the password ({@code auth}) or the directory user ({@code userauth}).
 * Not retried by default: a wrong password does not become right by reconnecting.
 */
public class AuthenticationException extends EslException {

    public AuthenticationException(String message) {
        super(message);
    }
}
