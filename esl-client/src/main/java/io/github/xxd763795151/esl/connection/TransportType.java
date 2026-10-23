package io.github.xxd763795151.esl.connection;

/**
 * Netty transport used for sockets.
 */
public enum TransportType {
    /** Best native transport available on the class path (epoll, then kqueue), otherwise NIO. */
    AUTO,
    NIO,
    /** Linux epoll; requires {@code netty-transport-native-epoll} with the matching classifier. */
    EPOLL,
    /** macOS/BSD kqueue; requires {@code netty-transport-native-kqueue}. */
    KQUEUE,
    /** Linux io_uring; requires {@code netty-transport-native-io_uring}. Never picked by {@link #AUTO}. */
    IO_URING
}
