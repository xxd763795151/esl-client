package io.github.xxd763795151.esl.event;

import io.github.xxd763795151.esl.protocol.EslHeaders;
import io.github.xxd763795151.esl.protocol.HeaderNames;

import java.time.Instant;
import java.util.Objects;

/**
 * An immutable FreeSWITCH event. Header values are already decoded regardless of the wire format.
 */
public final class EslEvent {

    private final EslHeaders headers;
    private final String body;
    private final EventFormat format;

    public EslEvent(EslHeaders headers, String body, EventFormat format) {
        this.headers = Objects.requireNonNull(headers, "headers");
        this.body = body == null ? "" : body;
        this.format = Objects.requireNonNull(format, "format");
    }

    /** {@code Event-Name}, e.g. {@code CHANNEL_ANSWER} or {@code CUSTOM}. */
    public String name() {
        return headers.getOrDefault(HeaderNames.EVENT_NAME, "");
    }

    /** {@code Event-Subclass} of {@code CUSTOM} events, otherwise {@code null}. */
    public String subclass() {
        return headers.get(HeaderNames.EVENT_SUBCLASS);
    }

    public boolean isNamed(String eventName) {
        return name().equalsIgnoreCase(eventName);
    }

    public boolean isCustom() {
        return isNamed(EventNames.CUSTOM);
    }

    /** {@code Unique-ID} of the channel the event belongs to, or {@code null} for non-channel events. */
    public String uniqueId() {
        return headers.get(HeaderNames.UNIQUE_ID);
    }

    /** {@code Job-UUID} of {@code BACKGROUND_JOB} events. */
    public String jobUuid() {
        return headers.get(HeaderNames.JOB_UUID);
    }

    public String application() {
        return headers.get(HeaderNames.APPLICATION);
    }

    public String applicationData() {
        return headers.get(HeaderNames.APPLICATION_DATA);
    }

    public String applicationResponse() {
        return headers.get(HeaderNames.APPLICATION_RESPONSE);
    }

    /** {@code Application-UUID}: the {@code Event-UUID} given to {@code sendmsg execute}. */
    public String applicationUuid() {
        return headers.get(HeaderNames.APPLICATION_UUID);
    }

    /** Channel variable carried by the event ({@code variable_<name>} header). */
    public String variable(String name) {
        return headers.get(HeaderNames.VARIABLE_PREFIX + name);
    }

    public String header(String name) {
        return headers.get(name);
    }

    public EslHeaders headers() {
        return headers;
    }

    /** Event body (e.g. the output of a {@code BACKGROUND_JOB}); empty when there is none. */
    public String body() {
        return body;
    }

    public boolean hasBody() {
        return !body.isEmpty();
    }

    public EventFormat format() {
        return format;
    }

    /** {@code Event-Sequence}, or -1 when absent. */
    public long sequence() {
        return headers.getLong(HeaderNames.EVENT_SEQUENCE, -1);
    }

    /** {@code Event-Date-Timestamp} (microseconds since epoch), or {@code null} when absent. */
    public Instant timestamp() {
        long micros = headers.getLong(HeaderNames.EVENT_DATE_TIMESTAMP, -1);
        if (micros < 0) {
            return null;
        }
        return Instant.ofEpochSecond(micros / 1_000_000, (micros % 1_000_000) * 1_000);
    }

    @Override
    public String toString() {
        StringBuilder text = new StringBuilder("EslEvent{").append(name());
        if (subclass() != null) {
            text.append(" (").append(subclass()).append(')');
        }
        if (uniqueId() != null) {
            text.append(", uuid=").append(uniqueId());
        }
        return text.append(", headers=").append(headers.size())
                .append(", bodyLength=").append(body.length())
                .append('}').toString();
    }
}
