package io.github.xxd763795151.esl.app;

import io.github.xxd763795151.esl.command.ApiResponse;
import io.github.xxd763795151.esl.command.CommandReply;
import io.github.xxd763795151.esl.command.Execute;
import io.github.xxd763795151.esl.command.ExecuteResult;
import io.github.xxd763795151.esl.command.SendMsg;
import io.github.xxd763795151.esl.connection.EslConnection;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;

/**
 * Call control for one channel, on top of {@link EslConnection#execute} (dialplan applications) and a few
 * {@code uuid_*} API commands. Works the same over an inbound client and an outbound session.
 * <p>
 * Application futures complete when the application has <em>finished</em> ({@code CHANNEL_EXECUTE_COMPLETE}),
 * so results such as collected digits are reliable, and fail with
 * {@link io.github.xxd763795151.esl.exception.ChannelHangupException} when the caller hangs up first.
 */
public final class CallChannel {

    private static final String UNDEFINED = "_undef_";

    private final EslConnection connection;
    private final String uuid;

    public CallChannel(EslConnection connection, String uuid) {
        this.connection = Objects.requireNonNull(connection, "connection");
        this.uuid = Objects.requireNonNull(uuid, "uuid");
    }

    public String uuid() {
        return uuid;
    }

    public EslConnection connection() {
        return connection;
    }

    // ------------------------------------------------------------------ generic

    public CompletableFuture<ExecuteResult> execute(String application) {
        return execute(Execute.of(application, null));
    }

    public CompletableFuture<ExecuteResult> execute(String application, String argument) {
        return execute(Execute.of(application, argument));
    }

    public CompletableFuture<ExecuteResult> execute(Execute execute) {
        return connection.execute(uuid, execute);
    }

    // ------------------------------------------------------------------ call flow

    public CompletableFuture<ExecuteResult> answer() {
        return execute("answer");
    }

    public CompletableFuture<ExecuteResult> preAnswer() {
        return execute("pre_answer");
    }

    public CompletableFuture<ExecuteResult> ringReady() {
        return execute("ring_ready");
    }

    /** Hangs up through {@code sendmsg} ({@code call-command: hangup}); cause defaults to NORMAL_CLEARING. */
    public CompletableFuture<CommandReply> hangup(String cause) {
        return connection.sendMsg(SendMsg.hangup(uuid, cause == null ? "NORMAL_CLEARING" : cause));
    }

    public CompletableFuture<CommandReply> hangup() {
        return hangup(null);
    }

    public CompletableFuture<ExecuteResult> park() {
        return execute("park");
    }

    public CompletableFuture<ExecuteResult> bridge(String dialString) {
        return execute("bridge", dialString);
    }

    /** {@code transfer <destination> [dialplan] [context]}. */
    public CompletableFuture<ExecuteResult> transfer(String destination, String dialplan, String context) {
        StringBuilder arg = new StringBuilder(destination);
        if (dialplan != null) {
            arg.append(' ').append(dialplan);
            if (context != null) {
                arg.append(' ').append(context);
            }
        }
        return execute("transfer", arg.toString());
    }

    public CompletableFuture<ExecuteResult> conference(String nameAndProfile) {
        return execute("conference", nameAndProfile);
    }

    public CompletableFuture<ExecuteResult> echo() {
        return execute("echo");
    }

    public CompletableFuture<ExecuteResult> sleep(Duration duration) {
        return execute("sleep", Long.toString(duration.toMillis()));
    }

    // ------------------------------------------------------------------ media

    public CompletableFuture<ExecuteResult> playback(String file) {
        return execute("playback", file);
    }

    /** {@code speak <engine>|<voice>|<text>}. */
    public CompletableFuture<ExecuteResult> speak(String engine, String voice, String text) {
        return execute("speak", engine + '|' + voice + '|' + text);
    }

    /**
     * Plays a prompt and collects digits. Completes with the digits, or {@code null} when nothing valid was
     * entered.
     */
    public CompletableFuture<String> playAndGetDigits(PlayAndGetDigits options) {
        return execute("play_and_get_digits", options.toArgument())
                .thenApply(result -> emptyToNull(result.variable(options.variable())));
    }

    /**
     * {@code read <min> <max> <sound> <variable> <timeout> <terminators>}; completes with the digits or
     * {@code null}.
     */
    public CompletableFuture<String> read(int minDigits, int maxDigits, String prompt, Duration timeout,
                                          String terminators) {
        String variable = PlayAndGetDigits.DEFAULT_VARIABLE;
        String arg = minDigits + " " + maxDigits + ' ' + prompt + ' ' + variable + ' ' + timeout.toMillis() + ' '
                + (terminators == null || terminators.isEmpty() ? "none" : terminators);
        return execute("read", arg).thenApply(result -> emptyToNull(result.variable(variable)));
    }

    /** {@code record <path> [max seconds] [silence threshold] [silence hits]}. */
    public CompletableFuture<ExecuteResult> record(String path, Duration maxLength, Integer silenceThreshold,
                                                   Integer silenceHits) {
        StringBuilder arg = new StringBuilder(path);
        if (maxLength != null) {
            arg.append(' ').append(maxLength.toSeconds());
            if (silenceThreshold != null) {
                arg.append(' ').append(silenceThreshold);
                if (silenceHits != null) {
                    arg.append(' ').append(silenceHits);
                }
            }
        }
        return execute("record", arg.toString());
    }

    public CompletableFuture<ExecuteResult> recordSession(String path) {
        return execute("record_session", path);
    }

    public CompletableFuture<ExecuteResult> stopRecordSession(String path) {
        return execute("stop_record_session", path);
    }

    public CompletableFuture<ExecuteResult> sendDtmf(String digits) {
        return execute("send_dtmf", digits);
    }

    /** Enables in-band DTMF detection. */
    public CompletableFuture<ExecuteResult> startDtmf() {
        return execute("start_dtmf");
    }

    public CompletableFuture<ExecuteResult> stopDtmf() {
        return execute("stop_dtmf");
    }

    // ------------------------------------------------------------------ variables

    /** {@code set} application: visible to the dialplan in order with other applications. */
    public CompletableFuture<ExecuteResult> set(String variable, String value) {
        return execute("set", variable + '=' + value);
    }

    public CompletableFuture<ExecuteResult> export(String variable, String value) {
        return execute("export", variable + '=' + value);
    }

    public CompletableFuture<ExecuteResult> unset(String variable) {
        return execute("unset", variable);
    }

    /** Reads a channel variable immediately ({@code uuid_getvar}); {@code null} when unset. */
    public CompletableFuture<String> getVariable(String variable) {
        return connection.api("uuid_getvar", uuid + ' ' + variable)
                .thenApply(ApiResponse::orThrow)
                .thenApply(response -> {
                    String value = response.trimmed();
                    return value.isEmpty() || UNDEFINED.equals(value) ? null : value;
                });
    }

    /** Sets a channel variable immediately ({@code uuid_setvar}), bypassing the application queue. */
    public CompletableFuture<ApiResponse> setVariable(String variable, String value) {
        return connection.api("uuid_setvar", uuid + ' ' + variable + ' ' + value).thenApply(ApiResponse::orThrow);
    }

    // ------------------------------------------------------------------ immediate control (api)

    /** Stops the media currently playing ({@code uuid_break}). */
    public CompletableFuture<ApiResponse> breakMedia(boolean all) {
        return connection.api("uuid_break", all ? uuid + " all" : uuid).thenApply(ApiResponse::orThrow);
    }

    /** Hangs up immediately ({@code uuid_kill}). */
    public CompletableFuture<ApiResponse> kill(String cause) {
        return connection.api("uuid_kill", cause == null ? uuid : uuid + ' ' + cause).thenApply(ApiResponse::orThrow);
    }

    /** Whether the channel still exists ({@code uuid_exists}). */
    public CompletableFuture<Boolean> exists() {
        return connection.api("uuid_exists", uuid).thenApply(response -> "true".equals(response.trimmed()));
    }

    private static String emptyToNull(String value) {
        return value == null || value.isEmpty() ? null : value;
    }

    @Override
    public String toString() {
        return "CallChannel[" + uuid + ']';
    }
}
