package io.github.xxd763795151.esl.outbound;

import io.github.xxd763795151.esl.event.EslEvent;

/**
 * Application logic for calls routed to the outbound socket server by the dialplan
 * ({@code <action application="socket" data="host:port async full"/>}).
 * <p>
 * One handler instance serves all sessions, so keep per-call state in {@link OutboundSession#attributes()} or
 * local variables.
 */
public interface OutboundSessionHandler {

    /**
     * The session is connected: {@link OutboundSession#channelData()} is available and, unless disabled, the
     * session receives its channel's events. Runs on a dedicated worker thread, so a sequential call script with
     * blocking {@code join()} calls is fine; events keep flowing to {@link #onEvent} meanwhile.
     */
    void onConnect(OutboundSession session) throws Exception;

    /** Events of the session, in order, on the session's event lane (never concurrently for one session). */
    default void onEvent(OutboundSession session, EslEvent event) throws Exception {
    }

    /**
     * The socket closed (hangup, {@code exit}, network error). Called after all events of the session.
     *
     * @param cause failure that closed it, or {@code null} for a regular close
     */
    default void onDisconnect(OutboundSession session, Throwable cause) {
    }

    /**
     * The server is shutting down gracefully and will close this session once the shutdown timeout expires.
     * Typically used to finish the call politely (play a message, transfer, hang up).
     */
    default void onServerShutdown(OutboundSession session) {
    }
}
