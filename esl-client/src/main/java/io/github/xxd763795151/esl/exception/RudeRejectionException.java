package io.github.xxd763795151.esl.exception;

/**
 * FreeSWITCH refused the connection ({@code text/rude-rejection}), typically because the client address is
 * not allowed by the event socket ACL ({@code apply-inbound-acl}).
 */
public class RudeRejectionException extends EslException {

    public RudeRejectionException(String message) {
        super(message);
    }
}
