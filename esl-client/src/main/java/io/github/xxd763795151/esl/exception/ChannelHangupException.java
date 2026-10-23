package io.github.xxd763795151.esl.exception;

/**
 * The channel hung up before the awaited application completed.
 */
public class ChannelHangupException extends EslException {

    private final String uuid;
    private final String hangupCause;

    public ChannelHangupException(String uuid, String hangupCause) {
        super("Channel " + uuid + " hung up" + (hangupCause == null ? "" : " (" + hangupCause + ")"));
        this.uuid = uuid;
        this.hangupCause = hangupCause;
    }

    public String uuid() {
        return uuid;
    }

    /** FreeSWITCH hangup cause such as {@code NORMAL_CLEARING}, may be {@code null}. */
    public String hangupCause() {
        return hangupCause;
    }
}
