package io.github.xxd763795151.esl.event;

import io.github.xxd763795151.esl.protocol.ContentTypes;

import java.util.Locale;

/**
 * Serialization format FreeSWITCH uses when delivering events to an event socket.
 */
public enum EventFormat {

    PLAIN(ContentTypes.EVENT_PLAIN),
    JSON(ContentTypes.EVENT_JSON),
    XML(ContentTypes.EVENT_XML);

    private final String contentType;

    EventFormat(String contentType) {
        this.contentType = contentType;
    }

    /** Keyword used on the wire, e.g. {@code event json CHANNEL_ANSWER}. */
    public String wireName() {
        return name().toLowerCase(Locale.ROOT);
    }

    public String contentType() {
        return contentType;
    }

    /** Resolves the format of an event frame, or {@code null} when the content type is not an event. */
    public static EventFormat fromContentType(String contentType) {
        for (EventFormat format : values()) {
            if (format.contentType.equalsIgnoreCase(contentType)) {
                return format;
            }
        }
        return null;
    }
}
