package io.github.xxd763795151.esl.event;

/**
 * Receives events. Invoked on a worker thread, never on a Netty I/O thread, so blocking calls are allowed
 * (but delay further events of the same ordering lane).
 */
@FunctionalInterface
public interface EventListener {

    void onEvent(EslEvent event) throws Exception;
}
