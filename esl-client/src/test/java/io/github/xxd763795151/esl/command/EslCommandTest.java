package io.github.xxd763795151.esl.command;

import io.github.xxd763795151.esl.event.EventFormat;
import io.github.xxd763795151.esl.internal.codec.EslCommandEncoder;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EslCommandTest {

    @Test
    void encodesSimpleCommands() {
        assertThat(wire(EslCommand.api("status"))).isEqualTo("api status\n\n");
        assertThat(wire(EslCommand.api("show", "channels as json"))).isEqualTo("api show channels as json\n\n");
        assertThat(wire(EslCommand.event(EventFormat.JSON, "CHANNEL_ANSWER", "CUSTOM", "sofia::register")))
                .isEqualTo("event json CHANNEL_ANSWER CUSTOM sofia::register\n\n");
        assertThat(wire(new EslCommand.MyEvents("abc", EventFormat.PLAIN))).isEqualTo("myevents abc plain\n\n");
        assertThat(wire(EslCommand.FilterDelete.all())).isEqualTo("filter delete all\n\n");
        assertThat(wire(new EslCommand.Linger(Duration.ofSeconds(10)))).isEqualTo("linger 10\n\n");
        assertThat(wire(new EslCommand.Log(LogLevel.DEBUG))).isEqualTo("log debug\n\n");
    }

    @Test
    void bgapiCarriesClientChosenJobUuid() {
        EslCommand.BgApi bgapi = new EslCommand.BgApi("originate", "user/1000 &park", "job-1");

        assertThat(wire(bgapi)).isEqualTo("bgapi originate user/1000 &park\nJob-UUID: job-1\n\n");
    }

    @Test
    void executeWithShortArgumentUsesHeaders() {
        Execute execute = Execute.builder("playback").argument("/tmp/a.wav").eventLock(true).loops(2).build();

        assertThat(wire(execute.toSendMsg("uuid-1", "ev-1"))).isEqualTo("""
                sendmsg uuid-1
                call-command: execute
                execute-app-name: playback
                execute-app-arg: /tmp/a.wav
                loops: 2
                event-lock: true
                Event-UUID: ev-1

                """);
    }

    @Test
    void executeWithMultiLineArgumentUsesBody() {
        String argument = "line one\n第二行";
        String wire = wire(Execute.of("speak", argument).toSendMsg("uuid-1", null));

        int bytes = argument.getBytes(StandardCharsets.UTF_8).length;
        assertThat(wire).startsWith("sendmsg uuid-1\ncall-command: execute\nexecute-app-name: speak\n")
                .contains("content-type: text/plain\n")
                .endsWith("Content-Length: " + bytes + "\n\n" + argument)
                .doesNotContain("execute-app-arg");
    }

    @Test
    void rejectsLineBreakInjection() {
        assertThatThrownBy(() -> EslCommand.api("status\n\nexit"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> EslCommand.api("uuid_kill", "x\nexit"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> SendMsg.builder("uuid").header("call-command", "execute\nexit").build())
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new EslCommand.Event(EventFormat.PLAIN, List.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void masksSecrets() {
        EslCommand auth = new EslCommand.Auth("secret");
        EslCommand userAuth = new EslCommand.UserAuth("1000@example.com", "secret");

        assertThat(auth.describe()).doesNotContain("secret");
        assertThat(auth.toString()).doesNotContain("secret");
        assertThat(userAuth.describe()).isEqualTo("userauth 1000@example.com:******");
        assertThat(wire(userAuth)).isEqualTo("userauth 1000@example.com:secret\n\n");
    }

    @Test
    void replyTypesAndNames() {
        assertThat(EslCommand.api("status").replyType()).isEqualTo(ReplyType.API_RESPONSE);
        assertThat(EslCommand.bgapi("status", null).replyType()).isEqualTo(ReplyType.COMMAND_REPLY);
        assertThat(SendMsg.hangup("u", null).name()).isEqualTo("sendmsg");
    }

    private static String wire(EslCommand command) {
        ByteBuf buffer = Unpooled.buffer();
        try {
            EslCommandEncoder.encode(command, buffer);
            return buffer.toString(StandardCharsets.UTF_8);
        } finally {
            buffer.release();
        }
    }
}
