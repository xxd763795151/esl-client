package io.github.xxd763795151.esl.command;

import java.nio.charset.StandardCharsets;
import java.util.Objects;

/**
 * Describes one dialplan application execution ({@code sendmsg} with {@code call-command: execute}).
 *
 * @param application     application name, e.g. {@code playback}
 * @param argument        application argument, may be {@code null}
 * @param eventLock       queue the application behind the ones already executing on the channel
 * @param async           execute without blocking the outbound socket's session thread
 * @param loops           how many times to run the application (at least 1)
 * @param awaitCompletion complete the returned future on {@code CHANNEL_EXECUTE_COMPLETE} instead of on
 *                        the {@code +OK} that merely confirms the message was queued
 */
public record Execute(String application, String argument, boolean eventLock, boolean async, int loops,
                      boolean awaitCompletion) {

    /** Arguments longer than this (or multi-line) are sent as the message body instead of a header. */
    static final int MAX_HEADER_ARGUMENT_BYTES = 1024;

    public Execute {
        Wire.token(application, "application");
        if (loops < 1) {
            throw new IllegalArgumentException("loops must be >= 1");
        }
    }

    public static Execute of(String application, String argument) {
        return new Execute(application, argument, false, false, 1, true);
    }

    public static Builder builder(String application) {
        return new Builder(application);
    }

    /**
     * Builds the {@code sendmsg} for this execution. {@code eventUuid} is echoed by FreeSWITCH as
     * {@code Application-UUID} in {@code CHANNEL_EXECUTE} / {@code CHANNEL_EXECUTE_COMPLETE}.
     */
    public SendMsg toSendMsg(String channelUuid, String eventUuid) {
        SendMsg.Builder builder = SendMsg.builder(channelUuid)
                .header("call-command", "execute")
                .header("execute-app-name", application);
        if (argument != null && !argument.isEmpty()) {
            boolean multiLine = argument.indexOf('\n') >= 0 || argument.indexOf('\r') >= 0;
            if (multiLine || argument.getBytes(StandardCharsets.UTF_8).length > MAX_HEADER_ARGUMENT_BYTES) {
                builder.body(argument);
            } else {
                builder.header("execute-app-arg", argument);
            }
        }
        if (loops > 1) {
            builder.header("loops", Integer.toString(loops));
        }
        if (eventLock) {
            builder.header("event-lock", "true");
        }
        if (async) {
            builder.header("async", "true");
        }
        if (eventUuid != null) {
            builder.header("Event-UUID", eventUuid);
        }
        return builder.build();
    }

    public static final class Builder {
        private final String application;
        private String argument;
        private boolean eventLock;
        private boolean async;
        private int loops = 1;
        private boolean awaitCompletion = true;

        private Builder(String application) {
            this.application = Objects.requireNonNull(application, "application");
        }

        public Builder argument(String argument) {
            this.argument = argument;
            return this;
        }

        public Builder eventLock(boolean eventLock) {
            this.eventLock = eventLock;
            return this;
        }

        public Builder async(boolean async) {
            this.async = async;
            return this;
        }

        public Builder loops(int loops) {
            this.loops = loops;
            return this;
        }

        /** Complete as soon as FreeSWITCH accepted the message, without waiting for the application to end. */
        public Builder fireAndForget() {
            this.awaitCompletion = false;
            return this;
        }

        public Execute build() {
            return new Execute(application, argument, eventLock, async, loops, awaitCompletion);
        }
    }
}
