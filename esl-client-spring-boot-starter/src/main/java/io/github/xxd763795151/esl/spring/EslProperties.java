package io.github.xxd763795151.esl.spring;

import io.github.xxd763795151.esl.command.LogLevel;
import io.github.xxd763795151.esl.connection.TransportType;
import io.github.xxd763795151.esl.dispatch.BackpressureStrategy;
import io.github.xxd763795151.esl.dispatch.DispatchMode;
import io.github.xxd763795151.esl.dispatch.DispatchOptions;
import io.github.xxd763795151.esl.event.EventFormat;
import io.github.xxd763795151.esl.reconnect.DisconnectedCommandPolicy;
import io.github.xxd763795151.esl.reconnect.ReconnectPolicy;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * {@code esl.*} configuration.
 *
 * <pre>
 * esl:
 *   inbound:
 *     enabled: true
 *     addresses: [10.0.0.10:8021, 10.0.0.11:8021]
 *     password: ClueCon
 *     events: [CHANNEL_ANSWER, CHANNEL_HANGUP_COMPLETE]
 *     reconnect:
 *       strategy: exponential
 *       max-delay: 30s
 *   inbounds:              # further clients, one per FreeSWITCH, injected with &#64;Qualifier("fs2")
 *     fs2:
 *       addresses: [10.0.1.10:8021]
 *   outbound:
 *     port: 8084
 *   outbounds:             # further servers, each bound to its own handler bean
 *     queue:
 *       port: 8085
 *       handler: queueHandler
 * </pre>
 *
 * @param inbound   inbound client
 * @param inbounds  further inbound clients by name; each entry stands alone (no inheritance from {@code inbound})
 * @param outbound  outbound server (started when an {@code OutboundSessionHandler} bean exists)
 * @param outbounds further outbound servers by name
 * @param threads   shared I/O and worker threads
 */
@ConfigurationProperties(prefix = "esl")
public record EslProperties(@DefaultValue Inbound inbound, Map<String, Inbound> inbounds,
                            @DefaultValue Outbound outbound, Map<String, Outbound> outbounds,
                            @DefaultValue Threads threads) {

    public EslProperties {
        inbounds = inbounds == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(inbounds));
        outbounds = outbounds == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(outbounds));
    }

    /**
     * @param enabled                   create and connect an {@code InboundClient}; defaults to {@code false} for
     *                                  {@code esl.inbound} and to {@code true} for {@code esl.inbounds} entries
     * @param name                      name used in logs, threads, metric tags and qualifiers; defaults to
     *                                  {@code esl}, or to the key of an {@code esl.inbounds} entry
     * @param addresses                 {@code host:port} entries, rotated on reconnect (fail-over)
     * @param password                  event socket password
     * @param user                      directory user for {@code userauth}; {@code auth} when empty
     * @param connectTimeout            TCP connect and auth/request timeout
     * @param commandTimeout            reply timeout of a command
     * @param jobTimeout                timeout of a {@code bgapi} job
     * @param executeTimeout            timeout of an awaited application execution, 0 = until hangup
     * @param idleTimeout               read idle time before a liveness probe, 0 = off
     * @param shutdownTimeout           graceful close timeout
     * @param startupWait               how long application startup waits for the first connection
     * @param failFast                  fail application startup when the first connection cannot be made
     * @param eventFormat               event format for subscriptions
     * @param events                    events subscribed on connect and after every reconnect
     * @param filters                   event filters (header to value) applied on connect
     * @param logLevel                  FreeSWITCH log level to stream, none when empty
     * @param disconnectedCommandPolicy what happens to commands issued while reconnecting
     * @param reconnect                 reconnect policy
     * @param dispatch                  event dispatch
     */
    public record Inbound(
            Boolean enabled,
            String name,
            @DefaultValue("127.0.0.1:8021") List<String> addresses,
            @DefaultValue("ClueCon") String password,
            String user,
            @DefaultValue("10s") Duration connectTimeout,
            @DefaultValue("30s") Duration commandTimeout,
            @DefaultValue("5m") Duration jobTimeout,
            @DefaultValue("0s") Duration executeTimeout,
            @DefaultValue("60s") Duration idleTimeout,
            @DefaultValue("10s") Duration shutdownTimeout,
            @DefaultValue("10s") Duration startupWait,
            @DefaultValue("false") boolean failFast,
            @DefaultValue("plain") EventFormat eventFormat,
            List<String> events,
            Map<String, String> filters,
            LogLevel logLevel,
            @DefaultValue("fail-fast") DisconnectedCommandPolicy disconnectedCommandPolicy,
            @DefaultValue Reconnect reconnect,
            @DefaultValue Dispatch dispatch) {

        public Inbound {
            events = events == null ? List.of() : List.copyOf(events);
            filters = filters == null ? Map.of() : Map.copyOf(filters);
        }

        boolean enabledOr(boolean fallback) {
            return enabled == null ? fallback : enabled;
        }

        String nameOr(String fallback) {
            return name == null || name.isBlank() ? fallback : name;
        }
    }

    /**
     * @param strategy          {@code none}, {@code fixed} or {@code exponential}
     * @param delay             delay of the fixed strategy
     * @param initialDelay      first delay of the exponential strategy
     * @param maxDelay          upper bound of the exponential strategy
     * @param multiplier        growth factor of the exponential strategy
     * @param jitter            randomisation of the exponential strategy, 0..1
     * @param maxAttempts       consecutive attempts before giving up, 0 = forever
     * @param retryAuthFailures also retry rejected credentials / ACL rejections
     */
    public record Reconnect(
            @DefaultValue("exponential") Strategy strategy,
            @DefaultValue("5s") Duration delay,
            @DefaultValue("1s") Duration initialDelay,
            @DefaultValue("30s") Duration maxDelay,
            @DefaultValue("2.0") double multiplier,
            @DefaultValue("0.2") double jitter,
            @DefaultValue("0") int maxAttempts,
            @DefaultValue("false") boolean retryAuthFailures) {

        public enum Strategy { NONE, FIXED, EXPONENTIAL }

        public ReconnectPolicy toPolicy() {
            ReconnectPolicy policy = switch (strategy) {
                case NONE -> ReconnectPolicy.none();
                case FIXED -> ReconnectPolicy.fixed(delay, maxAttempts);
                case EXPONENTIAL -> ReconnectPolicy.exponential()
                        .initialDelay(initialDelay)
                        .maxDelay(maxDelay)
                        .multiplier(multiplier)
                        .jitter(jitter)
                        .maxAttempts(maxAttempts)
                        .build();
            };
            return retryAuthFailures ? policy.retryingAllFailures() : policy;
        }
    }

    /**
     * @param mode           ordering guarantee
     * @param lanes          ordered lanes for per-channel ordering
     * @param highWaterMark  backlog that triggers backpressure
     * @param lowWaterMark   backlog at which reading resumes
     * @param backpressure   {@code pause-read} or {@code drop}
     */
    public record Dispatch(
            @DefaultValue("per-channel-ordered") DispatchMode mode,
            @DefaultValue("64") int lanes,
            @DefaultValue("10000") int highWaterMark,
            @DefaultValue("5000") int lowWaterMark,
            @DefaultValue("pause-read") BackpressureStrategy backpressure) {

        public DispatchOptions toOptions() {
            return new DispatchOptions(mode, lanes, highWaterMark, lowWaterMark, backpressure);
        }
    }

    /**
     * @param enabled            start the server when an {@code OutboundSessionHandler} bean exists
     * @param name               name used in logs, threads, metric tags and qualifiers; defaults to
     *                           {@code esl-outbound}, or to the key of an {@code esl.outbounds} entry
     * @param handler            bean name of the {@code OutboundSessionHandler}; may be omitted when only one exists
     * @param host               bind address
     * @param port               listening port
     * @param autoMyEvents       send {@code myevents} on connect
     * @param eventFormat        event format of {@code myevents}
     * @param linger             send {@code linger} with this duration, none when empty
     * @param divertEvents       send {@code divert_events on}
     * @param resumeOnDisconnect send {@code resume}
     * @param maxSessions        concurrent session limit, 0 = unlimited
     * @param commandTimeout     reply timeout of a command
     * @param jobTimeout         timeout of a {@code bgapi} job
     * @param executeTimeout     timeout of an awaited application execution, 0 = until hangup
     * @param shutdownTimeout    graceful shutdown timeout (waits for active calls)
     */
    public record Outbound(
            @DefaultValue("true") boolean enabled,
            String name,
            String handler,
            @DefaultValue("0.0.0.0") String host,
            @DefaultValue("8084") int port,
            @DefaultValue("true") boolean autoMyEvents,
            @DefaultValue("plain") EventFormat eventFormat,
            Duration linger,
            @DefaultValue("false") boolean divertEvents,
            @DefaultValue("false") boolean resumeOnDisconnect,
            @DefaultValue("0") int maxSessions,
            @DefaultValue("30s") Duration commandTimeout,
            @DefaultValue("5m") Duration jobTimeout,
            @DefaultValue("0s") Duration executeTimeout,
            @DefaultValue("30s") Duration shutdownTimeout) {

        String nameOr(String fallback) {
            return name == null || name.isBlank() ? fallback : name;
        }
    }

    /**
     * @param ioThreads      Netty I/O threads
     * @param virtualThreads run listeners and completions on virtual threads (Java 21+)
     * @param transport      {@code auto}, {@code nio}, {@code epoll}, {@code kqueue} or {@code io-uring}
     */
    public record Threads(
            @DefaultValue("4") int ioThreads,
            @DefaultValue("false") boolean virtualThreads,
            @DefaultValue("auto") TransportType transport) {
    }
}
