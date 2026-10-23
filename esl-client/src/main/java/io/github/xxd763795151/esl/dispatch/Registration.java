package io.github.xxd763795151.esl.dispatch;

/**
 * Handle of a registered listener. Removing it is idempotent.
 */
@FunctionalInterface
public interface Registration extends AutoCloseable {

    void remove();

    @Override
    default void close() {
        remove();
    }
}
