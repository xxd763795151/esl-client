package io.github.xxd763795151.esl.internal.netty;

import io.github.xxd763795151.esl.event.EslEvent;
import io.github.xxd763795151.esl.protocol.EslMessage;

/**
 * Unsolicited frames and channel lifecycle, reported by {@link EslProtocolHandler} on the event loop.
 */
interface ProtocolListener {

    void onAuthRequest();

    void onEvent(EslEvent event);

    void onLog(EslMessage message);

    void onDisconnectNotice(EslMessage message);

    void onRudeRejection(EslMessage message);

    void onReadIdle(int pendingCommands);

    void onChannelClosed(Throwable cause);
}
