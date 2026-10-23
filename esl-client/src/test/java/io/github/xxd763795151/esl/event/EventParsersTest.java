package io.github.xxd763795151.esl.event;

import io.github.xxd763795151.esl.protocol.EslHeaders;
import io.github.xxd763795151.esl.protocol.EslMessage;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EventParsersTest {

    @Test
    void parsesPlainEventWithEncodedValuesAndBody() {
        String body = """
                Event-Name: BACKGROUND_JOB
                Caller-Caller-ID-Number: %2B8613800000000
                Caller-Caller-ID-Name: %E5%BC%A0%E4%B8%89%20Zhang
                Broken: 100%25%20done%zz
                Event-Date-Timestamp: 1700000000123456
                Job-UUID: 7f4db78a-17d7-11dd-b7a0-db4edd065621
                Content-Length: 41

                +OK 7f4db78a-17d7-11dd-b7a0-db4edd065621
                """;
        EslEvent event = EventParsers.parse(frame("text/event-plain", body));

        assertThat(event.name()).isEqualTo("BACKGROUND_JOB");
        assertThat(event.header("Caller-Caller-ID-Number")).isEqualTo("+8613800000000");
        assertThat(event.header("caller-caller-id-name")).isEqualTo("张三 Zhang");
        assertThat(event.header("Broken")).isEqualTo("100% done%zz");
        assertThat(event.jobUuid()).isEqualTo("7f4db78a-17d7-11dd-b7a0-db4edd065621");
        assertThat(event.body()).isEqualTo("+OK 7f4db78a-17d7-11dd-b7a0-db4edd065621\n");
        assertThat(event.timestamp()).isEqualTo(Instant.ofEpochSecond(1_700_000_000L, 123_456_000L));
        assertThat(event.format()).isEqualTo(EventFormat.PLAIN);
    }

    @Test
    void parsesJsonEventWithArraysAndEscapes() {
        String body = """
                {"Event-Name":"CUSTOM","Event-Subclass":"sofia::register","Unique-ID":"u1",
                 "variable_list":["a","b"],"Quote":"say \\"hi\\" \\u4e2d","_body":"line1\\nline2"}
                """;
        EslEvent event = EventParsers.parse(frame("text/event-json", body));

        assertThat(event.isCustom()).isTrue();
        assertThat(event.subclass()).isEqualTo("sofia::register");
        assertThat(event.uniqueId()).isEqualTo("u1");
        assertThat(event.headers().getAll("variable_list")).containsExactly("a", "b");
        assertThat(event.header("Quote")).isEqualTo("say \"hi\" 中");
        assertThat(event.body()).isEqualTo("line1\nline2");
    }

    @Test
    void parsesXmlEvent() {
        String body = """
                <event>
                  <headers>
                    <Event-Name>CHANNEL_ANSWER</Event-Name>
                    <Unique-ID>u2</Unique-ID>
                    <Caller-Caller-ID-Name>A &amp; B</Caller-Caller-ID-Name>
                  </headers>
                  <body>hello</body>
                </event>
                """;
        EslEvent event = EventParsers.parse(frame("text/event-xml", body));

        assertThat(event.name()).isEqualTo("CHANNEL_ANSWER");
        assertThat(event.uniqueId()).isEqualTo("u2");
        assertThat(event.header("Caller-Caller-ID-Name")).isEqualTo("A & B");
        assertThat(event.body()).isEqualTo("hello");
    }

    @Test
    void rejectsXmlWithExternalEntities() {
        String body = """
                <?xml version="1.0"?>
                <!DOCTYPE event [<!ENTITY xxe SYSTEM "file:///etc/passwd">]>
                <event><headers><Event-Name>&xxe;</Event-Name></headers></event>
                """;

        assertThatThrownBy(() -> EventParsers.parse(frame("text/event-xml", body)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsMalformedJson() {
        assertThatThrownBy(() -> EventParsers.parse(frame("text/event-json", "{\"Event-Name\": ")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void decodesArrayHeaders() {
        EslHeaders headers = EslHeaders.builder().add("variable_codecs", "ARRAY::PCMU|:PCMA|:G722").build();

        assertThat(headers.getArray("variable_codecs")).containsExactly("PCMU", "PCMA", "G722");
    }

    private static EslMessage frame(String contentType, String body) {
        return new EslMessage(EslHeaders.builder().add("Content-Type", contentType).build(), body);
    }
}
