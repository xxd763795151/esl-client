package io.github.xxd763795151.esl.spring;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Registers a bean method as listener of the inbound clients. The method takes either no argument or one
 * {@link io.github.xxd763795151.esl.event.EslEvent}.
 * <p>
 * This only filters what the client receives; subscribe to the events with {@code esl.inbound.events}
 * ({@code esl.inbounds.<name>.events}).
 *
 * <pre>{@code
 * @EslEventListener("CHANNEL_HANGUP_COMPLETE")
 * void onHangup(EslEvent event) { ... }
 *
 * @EslEventListener(subclass = "sofia::register")
 * void onRegister(EslEvent event) { ... }
 *
 * @EslEventListener(value = "CHANNEL_ANSWER", client = "fs2")
 * void onAnswerAtFs2(EslEvent event) { ... }
 * }</pre>
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface EslEventListener {

    /** Event names to receive; empty (and no {@link #subclass()}) receives every event. */
    String[] value() default {};

    /** {@code CUSTOM} event subclasses to receive. */
    String[] subclass() default {};

    /** Names of the inbound clients to listen to (see {@link EslClients}); empty listens to all of them. */
    String[] client() default {};
}
