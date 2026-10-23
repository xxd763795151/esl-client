package io.github.xxd763795151.esl.command;

import io.github.xxd763795151.esl.protocol.EslHeaders;
import io.github.xxd763795151.esl.protocol.HeaderNames;

/**
 * {@code sendevent <name>}: fires an event inside FreeSWITCH (e.g. {@code NOTIFY}, {@code SEND_MESSAGE},
 * or {@code CUSTOM} with an {@code Event-Subclass}). The reply carries {@code +OK <event-uuid>}.
 */
public record SendEvent(String eventName, EslHeaders headers, String body) implements EslCommand {

    public SendEvent {
        Wire.token(eventName, "eventName");
        headers = Wire.headers(headers);
    }

    public static Builder builder(String eventName) {
        return new Builder(eventName);
    }

    /** {@code CUSTOM} event with the given subclass. */
    public static Builder custom(String subclass) {
        return new Builder("CUSTOM").header(HeaderNames.EVENT_SUBCLASS, subclass);
    }

    @Override
    public String commandLine() {
        return "sendevent " + eventName;
    }

    public static final class Builder {
        private final String eventName;
        private final EslHeaders.Builder headers = EslHeaders.builder();
        private String body;

        private Builder(String eventName) {
            this.eventName = eventName;
        }

        public Builder header(String name, String value) {
            headers.add(name, value);
            return this;
        }

        public Builder body(String body) {
            this.body = body;
            return this;
        }

        public SendEvent build() {
            return new SendEvent(eventName, headers.build(), body);
        }
    }
}
