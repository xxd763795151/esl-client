package io.github.xxd763795151.esl.protocol;

import java.util.Objects;

/**
 * One decoded frame received from FreeSWITCH: a header block plus an optional body.
 *
 * @param headers frame headers
 * @param body    body decoded as UTF-8, empty when the frame has no {@code Content-Length}
 */
public record EslMessage(EslHeaders headers, String body) {

    public EslMessage {
        Objects.requireNonNull(headers, "headers");
        body = body == null ? "" : body;
    }

    public String contentType() {
        return headers.getOrDefault(HeaderNames.CONTENT_TYPE, "");
    }

    /** {@code Reply-Text} header, or {@code null} for frames that carry none. */
    public String replyText() {
        return headers.get(HeaderNames.REPLY_TEXT);
    }

    public String header(String name) {
        return headers.get(name);
    }

    public boolean hasBody() {
        return !body.isEmpty();
    }

    public boolean isContentType(String type) {
        return contentType().equalsIgnoreCase(type);
    }

    @Override
    public String toString() {
        return "EslMessage{contentType=" + contentType()
                + ", headers=" + headers.size()
                + ", bodyLength=" + body.length() + '}';
    }
}
