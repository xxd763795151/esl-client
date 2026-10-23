package io.github.xxd763795151.esl.connection;

import io.github.xxd763795151.esl.app.CallChannel;
import io.github.xxd763795151.esl.command.ApiResponse;
import io.github.xxd763795151.esl.command.BackgroundJobResult;
import io.github.xxd763795151.esl.command.CommandReply;
import io.github.xxd763795151.esl.command.EslCommand;
import io.github.xxd763795151.esl.command.Execute;
import io.github.xxd763795151.esl.command.ExecuteResult;
import io.github.xxd763795151.esl.command.LogLevel;
import io.github.xxd763795151.esl.command.SendEvent;
import io.github.xxd763795151.esl.command.SendMsg;
import io.github.xxd763795151.esl.dispatch.Registration;
import io.github.xxd763795151.esl.event.EventListener;
import io.github.xxd763795151.esl.event.EventSelector;
import io.github.xxd763795151.esl.event.LogListener;
import io.github.xxd763795151.esl.protocol.EslMessage;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;

/**
 * Operations shared by inbound clients and outbound sessions.
 * <p>
 * Every operation is asynchronous and never blocks. Returned futures complete on a worker thread, never on a
 * Netty I/O thread, so it is safe to chain further calls or even {@code join()} inside callbacks. Joining on an
 * I/O thread is detected and rejected instead of deadlocking.
 * <p>
 * Methods returning {@link CommandReply} fail with
 * {@link io.github.xxd763795151.esl.exception.CommandFailedException} when FreeSWITCH answers {@code -ERR}.
 * All of them fail with {@link io.github.xxd763795151.esl.exception.CommandTimeoutException} when no reply
 * arrives in time and with {@link io.github.xxd763795151.esl.exception.ConnectionClosedException} when the
 * connection is not usable.
 */
public interface EslConnection extends AutoCloseable {

    ConnectionState state();

    default boolean isReady() {
        return state() == ConnectionState.READY;
    }

    /** Sends any command and returns the raw reply frame, without interpreting {@code -ERR}. */
    CompletableFuture<EslMessage> send(EslCommand command);

    /** {@code api <command> <arguments>}: synchronous on the FreeSWITCH side; prefer {@link #bgapi} for slow ones. */
    CompletableFuture<ApiResponse> api(String command, String arguments);

    /** {@code api} with the command line split at the first space, e.g. {@code api("show channels")}. */
    default CompletableFuture<ApiResponse> api(String commandLine) {
        int space = commandLine.indexOf(' ');
        return space < 0 ? api(commandLine, null)
                : api(commandLine.substring(0, space), commandLine.substring(space + 1));
    }

    /**
     * {@code bgapi}: completes with the {@code BACKGROUND_JOB} result. Subscribes to {@code BACKGROUND_JOB}
     * automatically when needed.
     */
    CompletableFuture<BackgroundJobResult> bgapi(String command, String arguments);

    default CompletableFuture<BackgroundJobResult> bgapi(String commandLine) {
        int space = commandLine.indexOf(' ');
        return space < 0 ? bgapi(commandLine, null)
                : bgapi(commandLine.substring(0, space), commandLine.substring(space + 1));
    }

    /** {@code event <format> <names>}: adds subscriptions in the connection's configured format. */
    CompletableFuture<CommandReply> subscribe(String... events);

    /** {@code nixevent <names>}. */
    CompletableFuture<CommandReply> unsubscribe(String... events);

    /** {@code noevents}. */
    CompletableFuture<CommandReply> unsubscribeAll();

    CompletableFuture<CommandReply> addFilter(String header, String value);

    /** Removes one filter; a {@code null} value removes every filter on the header. */
    CompletableFuture<CommandReply> removeFilter(String header, String value);

    CompletableFuture<CommandReply> removeAllFilters();

    CompletableFuture<CommandReply> setLogLevel(LogLevel level);

    CompletableFuture<CommandReply> disableLog();

    CompletableFuture<CommandReply> sendMsg(SendMsg message);

    CompletableFuture<CommandReply> sendEvent(SendEvent event);

    /**
     * Runs a dialplan application on a channel. Unless the execution is fire-and-forget, the future completes
     * on {@code CHANNEL_EXECUTE_COMPLETE}, matched through the {@code Event-UUID} sent with the message, and
     * fails with {@link io.github.xxd763795151.esl.exception.ChannelHangupException} if the channel hangs up
     * first.
     */
    CompletableFuture<ExecuteResult> execute(String uuid, Execute execute);

    /** Call control facade for one channel. */
    default CallChannel channel(String uuid) {
        return new CallChannel(this, uuid);
    }

    Registration addEventListener(EventSelector selector, EventListener listener);

    default Registration addEventListener(EventListener listener) {
        return addEventListener(EventSelector.all(), listener);
    }

    Registration addLogListener(LogListener listener);

    Registration addStateListener(ConnectionStateListener listener);

    /**
     * Closes in stages, each bounded by the timeout: stop accepting commands, let in-flight replies arrive,
     * send {@code exit}, half-close and wait for FreeSWITCH to close, and finally reset the TCP connection
     * ({@code SO_LINGER=0}) if the peer still has not let go. Resources owned by the connection are released
     * afterwards. Idempotent.
     */
    CompletableFuture<Void> closeGracefully(Duration timeout);

    /** {@link #closeGracefully(Duration)} with the configured default timeout, waiting for completion. */
    @Override
    void close();
}
