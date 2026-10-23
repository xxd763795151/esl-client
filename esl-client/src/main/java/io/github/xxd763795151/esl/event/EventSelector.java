package io.github.xxd763795151.esl.event;

import java.util.Arrays;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Decides which events a listener receives. Selectors only filter locally; FreeSWITCH only sends what the
 * connection subscribed to.
 */
@FunctionalInterface
public interface EventSelector {

    boolean matches(EslEvent event);

    static EventSelector all() {
        return event -> true;
    }

    /** Events with one of the given {@code Event-Name}s (case-insensitive). */
    static EventSelector named(String... eventNames) {
        Set<String> names = Arrays.stream(eventNames)
                .map(name -> name.toUpperCase(Locale.ROOT))
                .collect(Collectors.toUnmodifiableSet());
        return event -> names.contains(event.name().toUpperCase(Locale.ROOT));
    }

    /** {@code CUSTOM} events with one of the given subclasses, e.g. {@code sofia::register}. */
    static EventSelector custom(String... subclasses) {
        Set<String> names = Set.of(subclasses);
        return event -> event.isCustom() && names.contains(event.subclass());
    }

    /** Events of one channel ({@code Unique-ID}). */
    static EventSelector channel(String uuid) {
        Objects.requireNonNull(uuid, "uuid");
        return event -> uuid.equals(event.uniqueId());
    }

    default EventSelector and(EventSelector other) {
        Objects.requireNonNull(other, "other");
        return event -> matches(event) && other.matches(event);
    }

    default EventSelector or(EventSelector other) {
        Objects.requireNonNull(other, "other");
        return event -> matches(event) || other.matches(event);
    }

    default EventSelector negate() {
        return event -> !matches(event);
    }
}
