package io.github.xxd763795151.esl.internal.codec;

import io.github.xxd763795151.esl.protocol.EslMessage;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.DecoderException;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EslFrameDecoderTest {

    private static final String REPLY = "Content-Type: command/reply\nReply-Text: +OK accepted\n\n";
    private static final String BODY = "UP 0 years, 0 days\n中文 ✓\n";
    private static final String API = "Content-Type: api/response\nContent-Length: "
            + BODY.getBytes(StandardCharsets.UTF_8).length + "\n\n" + BODY;

    @Test
    void decodesHeaderOnlyFrame() {
        List<EslMessage> messages = decode(REPLY);

        assertThat(messages).hasSize(1);
        assertThat(messages.get(0).contentType()).isEqualTo("command/reply");
        assertThat(messages.get(0).replyText()).isEqualTo("+OK accepted");
        assertThat(messages.get(0).body()).isEmpty();
    }

    @Test
    void decodesUtf8BodyByContentLength() {
        List<EslMessage> messages = decode(API);

        assertThat(messages).singleElement().satisfies(message -> {
            assertThat(message.contentType()).isEqualTo("api/response");
            assertThat(message.body()).isEqualTo(BODY);
        });
    }

    @Test
    void decodesFramesSplitAtEveryByte() {
        EmbeddedChannel channel = new EmbeddedChannel(new EslFrameDecoder(1024, 1024));
        byte[] bytes = (REPLY + API + REPLY).getBytes(StandardCharsets.UTF_8);
        for (byte b : bytes) {
            channel.writeInbound(Unpooled.wrappedBuffer(new byte[]{b}));
        }
        List<EslMessage> messages = drain(channel);

        assertThat(messages).extracting(EslMessage::contentType)
                .containsExactly("command/reply", "api/response", "command/reply");
        assertThat(messages.get(1).body()).isEqualTo(BODY);
        assertThat(channel.finish()).isFalse();
    }

    @Test
    void decodesSeveralFramesFromOneBuffer() {
        List<EslMessage> messages = decode(REPLY + API + "Content-Type: auth/request\n\n");

        assertThat(messages).extracting(EslMessage::contentType)
                .containsExactly("command/reply", "api/response", "auth/request");
    }

    @Test
    void keepsUnknownAndRepeatedHeaders() {
        List<EslMessage> messages = decode("Content-Type: log/data\nLog-Level: 7\nX-Custom: a\nX-Custom: b\n"
                + "Content-Length: 3\n\nabc");

        EslMessage message = messages.get(0);
        assertThat(message.header("log-level")).isEqualTo("7");
        assertThat(message.headers().getAll("X-CUSTOM")).containsExactly("a", "b");
        assertThat(message.body()).isEqualTo("abc");
    }

    @Test
    void toleratesCarriageReturnsAndLeadingBlankLines() {
        List<EslMessage> messages = decode("\n\nContent-Type: command/reply\r\nReply-Text: +OK\r\n\n");

        assertThat(messages).singleElement().satisfies(message -> {
            assertThat(message.contentType()).isEqualTo("command/reply");
            assertThat(message.replyText()).isEqualTo("+OK");
        });
    }

    @Test
    void rejectsOversizedHeaderBlock() {
        EmbeddedChannel channel = new EmbeddedChannel(new EslFrameDecoder(32, 1024));

        assertThatThrownBy(() -> channel.writeInbound(buffer("Content-Type: command/reply\nReply-Text: +OK long\n\n")))
                .isInstanceOf(DecoderException.class);
    }

    @Test
    void rejectsOversizedBody() {
        EmbeddedChannel channel = new EmbeddedChannel(new EslFrameDecoder(1024, 4));

        assertThatThrownBy(() -> channel.writeInbound(buffer("Content-Type: api/response\nContent-Length: 10\n\n")))
                .isInstanceOf(DecoderException.class);
    }

    private static List<EslMessage> decode(String text) {
        EmbeddedChannel channel = new EmbeddedChannel(new EslFrameDecoder(1024, 1024));
        channel.writeInbound(buffer(text));
        List<EslMessage> messages = drain(channel);
        channel.finishAndReleaseAll();
        return messages;
    }

    private static List<EslMessage> drain(EmbeddedChannel channel) {
        List<EslMessage> messages = new ArrayList<>();
        EslMessage message;
        while ((message = channel.readInbound()) != null) {
            messages.add(message);
        }
        return messages;
    }

    private static ByteBuf buffer(String text) {
        return Unpooled.copiedBuffer(text, StandardCharsets.UTF_8);
    }
}
