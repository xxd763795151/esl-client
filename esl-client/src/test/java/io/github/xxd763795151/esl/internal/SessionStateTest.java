package io.github.xxd763795151.esl.internal;

import io.github.xxd763795151.esl.command.EslCommand;
import io.github.xxd763795151.esl.command.LogLevel;
import io.github.xxd763795151.esl.event.EventFormat;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SessionStateTest {

    @Test
    void replaysSubscriptionsWithCustomSubclassesLast() {
        SessionState state = new SessionState();
        state.record(new EslCommand.Event(EventFormat.JSON, List.of("CUSTOM", "sofia::register", "conference::maintenance")));
        state.record(new EslCommand.Event(EventFormat.JSON, List.of("channel_answer", "BACKGROUND_JOB")));
        state.record(new EslCommand.NixEvent(List.of("CUSTOM", "conference::maintenance")));
        state.record(new EslCommand.Filter("Event-Name", "CHANNEL_ANSWER"));
        state.record(new EslCommand.Filter("Unique-ID", "u1"));
        state.record(new EslCommand.FilterDelete("Unique-ID", null));
        state.record(new EslCommand.Log(LogLevel.INFO));

        assertThat(state.replayCommands(EventFormat.PLAIN)).extracting(EslCommand::commandLine).containsExactly(
                "event json CHANNEL_ANSWER BACKGROUND_JOB CUSTOM sofia::register",
                "filter Event-Name CHANNEL_ANSWER",
                "log info");
        assertThat(state.isSubscribed("channel_answer")).isTrue();
        assertThat(state.isSubscribed("CHANNEL_HANGUP")).isFalse();
    }

    @Test
    void allCoversEverythingAndNoEventsClears() {
        SessionState state = new SessionState();
        state.record(new EslCommand.Event(EventFormat.PLAIN, List.of("ALL")));

        assertThat(state.isSubscribed("BACKGROUND_JOB")).isTrue();

        state.record(new EslCommand.NoEvents());
        state.record(EslCommand.FilterDelete.all());

        assertThat(state.isSubscribed("BACKGROUND_JOB")).isFalse();
        assertThat(state.replayCommands(EventFormat.PLAIN)).isEmpty();
    }

    @Test
    void removingAnEventUnderAllExcludesItAndReplaysTheExclusion() {
        SessionState state = new SessionState();
        state.record(new EslCommand.Event(EventFormat.PLAIN, List.of("ALL")));
        state.record(new EslCommand.NixEvent(List.of("channel_execute_complete")));

        assertThat(state.isSubscribed("CHANNEL_EXECUTE_COMPLETE")).isFalse();
        assertThat(state.isSubscribed("CHANNEL_ANSWER")).isTrue();
        assertThat(state.replayCommands(EventFormat.PLAIN)).extracting(EslCommand::commandLine).containsExactly(
                "event plain ALL",
                "nixevent CHANNEL_EXECUTE_COMPLETE");

        state.record(new EslCommand.Event(EventFormat.PLAIN, List.of("CHANNEL_EXECUTE_COMPLETE")));

        assertThat(state.isSubscribed("CHANNEL_EXECUTE_COMPLETE")).isTrue();
        assertThat(state.replayCommands(EventFormat.PLAIN)).extracting(EslCommand::commandLine)
                .containsExactly("event plain ALL CHANNEL_EXECUTE_COMPLETE");
    }

    @Test
    void subscribingOrRemovingAllForgetsExclusions() {
        SessionState state = new SessionState();
        state.record(new EslCommand.Event(EventFormat.PLAIN, List.of("ALL")));
        state.record(new EslCommand.NixEvent(List.of("DTMF", "CUSTOM", "sofia::register")));

        assertThat(state.isSubscribed("DTMF")).isFalse();
        // like FreeSWITCH, nixevent CUSTOM only switches to subclasses
        assertThat(state.isSubscribed("CUSTOM")).isTrue();

        state.record(new EslCommand.Event(EventFormat.PLAIN, List.of("ALL")));

        assertThat(state.isSubscribed("DTMF")).isTrue();

        state.record(new EslCommand.NixEvent(List.of("DTMF")));
        state.record(new EslCommand.NixEvent(List.of("ALL")));

        assertThat(state.isSubscribed("DTMF")).isFalse();
        assertThat(state.isSubscribed("CHANNEL_ANSWER")).isFalse();
        assertThat(state.replayCommands(EventFormat.PLAIN)).isEmpty();
    }
}
