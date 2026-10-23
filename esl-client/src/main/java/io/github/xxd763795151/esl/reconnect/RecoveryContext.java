package io.github.xxd763795151.esl.reconnect;

import io.github.xxd763795151.esl.command.EslCommand;
import io.github.xxd763795151.esl.protocol.EslMessage;

import java.net.SocketAddress;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * What a {@link RecoveryStrategy} can do on a freshly authenticated socket, before the client reports
 * {@code READY} and releases queued commands.
 */
public interface RecoveryContext {

    /** {@code false} for the initial connect, {@code true} after a connection loss. */
    boolean isReconnect();

    SocketAddress remoteAddress();

    /** Commands that rebuild the recorded subscriptions, filters and log level. */
    List<EslCommand> recordedState();

    /** Sends {@link #recordedState()} in order; fails on the first {@code -ERR}. */
    CompletableFuture<Void> replayRecordedState();

    /** Forgets the recorded state (the new socket starts empty, so the record must match it). */
    void discardRecordedState();

    /**
     * Sends a command on the new socket. Successful stateful commands are recorded and survive later
     * reconnects too.
     */
    CompletableFuture<EslMessage> send(EslCommand command);
}
