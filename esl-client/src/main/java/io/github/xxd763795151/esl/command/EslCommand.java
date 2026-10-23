package io.github.xxd763795151.esl.command;

import io.github.xxd763795151.esl.event.EventFormat;
import io.github.xxd763795151.esl.protocol.EslHeaders;
import io.github.xxd763795151.esl.protocol.HeaderNames;

import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;

/**
 * A command sent to FreeSWITCH over the event socket.
 * <p>
 * On the wire a command is its {@link #commandLine()}, followed by optional {@link #headers()} and an optional
 * {@link #body()} (sent with a {@code Content-Length} header), terminated by an empty line. Every command is
 * answered by exactly one reply frame of type {@link #replyType()}, which is what makes FIFO reply correlation
 * possible.
 * <p>
 * The interface is sealed: the set of protocol commands is closed, and {@link Raw} is the escape hatch for
 * anything not modelled explicitly.
 */
public sealed interface EslCommand permits EslCommand.Auth, EslCommand.UserAuth, EslCommand.Api, EslCommand.BgApi,
        EslCommand.Event, EslCommand.NixEvent, EslCommand.NoEvents, EslCommand.Filter, EslCommand.FilterDelete,
        EslCommand.MyEvents, EslCommand.DivertEvents, EslCommand.Log, EslCommand.NoLog, EslCommand.Linger,
        EslCommand.NoLinger, EslCommand.Connect, EslCommand.Resume, EslCommand.GetVar, EslCommand.Exit,
        EslCommand.Raw, SendMsg, SendEvent {

    /** First line of the command, e.g. {@code api status}. */
    String commandLine();

    /** Extra headers following the command line. */
    default EslHeaders headers() {
        return EslHeaders.empty();
    }

    /** Optional body; when non-null the encoder adds the {@code Content-Length} header. */
    default String body() {
        return null;
    }

    default ReplyType replyType() {
        return ReplyType.COMMAND_REPLY;
    }

    /** Lower-case command keyword ({@code api}, {@code sendmsg} ...), suitable as a metric tag. */
    default String name() {
        String line = commandLine();
        int space = line.indexOf(' ');
        return (space < 0 ? line : line.substring(0, space)).toLowerCase(Locale.ROOT);
    }

    /** Text safe for logging: secrets are masked. */
    default String describe() {
        return commandLine();
    }

    // ------------------------------------------------------------------ factories

    static Api api(String command) {
        return new Api(command, null);
    }

    static Api api(String command, String arguments) {
        return new Api(command, arguments);
    }

    static BgApi bgapi(String command, String arguments) {
        return new BgApi(command, arguments, UUID.randomUUID().toString());
    }

    static Event event(EventFormat format, String... events) {
        return new Event(format, List.of(events));
    }

    // ------------------------------------------------------------------ commands

    /** {@code auth <password>}, answering the {@code auth/request} of an inbound connection. */
    record Auth(String password) implements EslCommand {
        public Auth {
            Wire.line(password, "password");
        }

        @Override
        public String commandLine() {
            return "auth " + password;
        }

        @Override
        public String describe() {
            return "auth ******";
        }

        @Override
        public String toString() {
            return "Auth[******]";
        }
    }

    /** {@code userauth <user>[@domain]:<password>}, authenticating as a directory user. */
    record UserAuth(String user, String password) implements EslCommand {
        public UserAuth {
            Wire.token(user, "user");
            Wire.line(password, "password");
        }

        @Override
        public String commandLine() {
            return "userauth " + user + ':' + password;
        }

        @Override
        public String describe() {
            return "userauth " + user + ":******";
        }

        @Override
        public String toString() {
            return "UserAuth[user=" + user + ", password=******]";
        }
    }

    /** {@code api <command> [arguments]}: runs synchronously, the output arrives as {@code api/response}. */
    record Api(String command, String arguments) implements EslCommand {
        public Api {
            Wire.token(command, "command");
            Wire.optionalLine(arguments, "arguments");
        }

        @Override
        public String commandLine() {
            return Wire.join("api " + command, arguments);
        }

        @Override
        public ReplyType replyType() {
            return ReplyType.API_RESPONSE;
        }
    }

    /**
     * {@code bgapi <command> [arguments]} with a client chosen {@code Job-UUID}: FreeSWITCH reuses it in the
     * {@code BACKGROUND_JOB} event, so the result can be awaited without any race.
     */
    record BgApi(String command, String arguments, String jobUuid) implements EslCommand {
        public BgApi {
            Wire.token(command, "command");
            Wire.optionalLine(arguments, "arguments");
            Wire.token(jobUuid, "jobUuid");
        }

        @Override
        public String commandLine() {
            return Wire.join("bgapi " + command, arguments);
        }

        @Override
        public EslHeaders headers() {
            return EslHeaders.builder().add(HeaderNames.JOB_UUID, jobUuid).build();
        }
    }

    /** {@code event <format> <names...>}: adds event subscriptions; {@code CUSTOM a::b} subclasses allowed. */
    record Event(EventFormat format, List<String> events) implements EslCommand {
        public Event {
            Objects.requireNonNull(format, "format");
            events = List.copyOf(Wire.words(events, "events"));
        }

        @Override
        public String commandLine() {
            return "event " + format.wireName() + ' ' + String.join(" ", events);
        }
    }

    /** {@code nixevent <names...>}: removes event subscriptions. */
    record NixEvent(List<String> events) implements EslCommand {
        public NixEvent {
            events = List.copyOf(Wire.words(events, "events"));
        }

        @Override
        public String commandLine() {
            return "nixevent " + String.join(" ", events);
        }
    }

    /** {@code noevents}: removes all event subscriptions. */
    record NoEvents() implements EslCommand {
        @Override
        public String commandLine() {
            return "noevents";
        }
    }

    /** {@code filter <header> <value>}: only deliver events whose header matches. */
    record Filter(String header, String value) implements EslCommand {
        public Filter {
            Wire.token(header, "header");
            Wire.line(value, "value");
        }

        @Override
        public String commandLine() {
            return "filter " + header + ' ' + value;
        }
    }

    /** {@code filter delete <header> [value]}, or {@code filter delete all}. */
    record FilterDelete(String header, String value) implements EslCommand {
        public FilterDelete {
            Wire.token(header, "header");
            Wire.optionalLine(value, "value");
        }

        public static FilterDelete all() {
            return new FilterDelete("all", null);
        }

        @Override
        public String commandLine() {
            return Wire.join("filter delete " + header, value);
        }
    }

    /**
     * {@code myevents [uuid] [format]}: receive all events of one channel. Outbound sessions omit the uuid.
     */
    record MyEvents(String uuid, EventFormat format) implements EslCommand {
        public MyEvents {
            if (uuid != null) {
                Wire.token(uuid, "uuid");
            }
        }

        @Override
        public String commandLine() {
            StringBuilder line = new StringBuilder("myevents");
            if (uuid != null) {
                line.append(' ').append(uuid);
            }
            if (format != null) {
                line.append(' ').append(format.wireName());
            }
            return line.toString();
        }
    }

    /** {@code divert_events on|off}: deliver events of embedded language input callbacks to the socket. */
    record DivertEvents(boolean enabled) implements EslCommand {
        @Override
        public String commandLine() {
            return "divert_events " + (enabled ? "on" : "off");
        }
    }

    /** {@code log <level>}: stream FreeSWITCH log lines as {@code log/data} frames. */
    record Log(LogLevel level) implements EslCommand {
        public Log {
            Objects.requireNonNull(level, "level");
        }

        @Override
        public String commandLine() {
            return "log " + level.wireName();
        }
    }

    /** {@code nolog}: stop log streaming. */
    record NoLog() implements EslCommand {
        @Override
        public String commandLine() {
            return "nolog";
        }
    }

    /**
     * {@code linger [seconds]}: keep an outbound socket open after the channel hangs up so the final events
     * (e.g. {@code CHANNEL_HANGUP_COMPLETE}) are still delivered. {@code null} uses the server default.
     */
    record Linger(Duration duration) implements EslCommand {
        @Override
        public String commandLine() {
            return duration == null ? "linger" : "linger " + Math.max(1, duration.toSeconds());
        }
    }

    /** {@code nolinger}. */
    record NoLinger() implements EslCommand {
        @Override
        public String commandLine() {
            return "nolinger";
        }
    }

    /** {@code connect}: first command of an outbound session; the reply carries the channel data. */
    record Connect() implements EslCommand {
        @Override
        public String commandLine() {
            return "connect";
        }
    }

    /** {@code resume}: let the dialplan continue when the outbound socket disconnects. */
    record Resume() implements EslCommand {
        @Override
        public String commandLine() {
            return "resume";
        }
    }

    /** {@code getvar <name>}: read a variable of the outbound session's channel. */
    record GetVar(String variable) implements EslCommand {
        public GetVar {
            Wire.token(variable, "variable");
        }

        @Override
        public String commandLine() {
            return "getvar " + variable;
        }
    }

    /** {@code exit}: ask FreeSWITCH to close the socket. */
    record Exit() implements EslCommand {
        @Override
        public String commandLine() {
            return "exit";
        }
    }

    /** Any command not modelled above. The caller states which reply type FreeSWITCH answers with. */
    record Raw(String commandLine, EslHeaders headers, String body, ReplyType replyType) implements EslCommand {
        public Raw {
            Wire.line(commandLine, "commandLine");
            if (commandLine.isBlank()) {
                throw new IllegalArgumentException("commandLine must not be blank");
            }
            headers = Wire.headers(headers);
            Objects.requireNonNull(replyType, "replyType");
        }

        public Raw(String commandLine) {
            this(commandLine, EslHeaders.empty(), null, ReplyType.COMMAND_REPLY);
        }
    }
}
