package io.github.xxd763795151.esl.protocol;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Immutable, insertion ordered, case-insensitive multimap of ESL header names to values.
 * <p>
 * FreeSWITCH header names are case-insensitive on the wire ({@code Content-Type} vs {@code content-type}),
 * and a few headers may legitimately appear more than once, so every name maps to a list of values.
 */
public final class EslHeaders {

    private static final EslHeaders EMPTY = new EslHeaders(Map.of());
    private static final String ARRAY_PREFIX = "ARRAY::";
    private static final String ARRAY_SEPARATOR = "|:";

    /** lower-cased name -> entry holding the original name and its values */
    private final Map<String, Entry> entries;

    private EslHeaders(Map<String, Entry> entries) {
        this.entries = entries;
    }

    public static EslHeaders empty() {
        return EMPTY;
    }

    public static Builder builder() {
        return new Builder();
    }

    /** Creates headers from a single-valued map, keeping its iteration order. */
    public static EslHeaders of(Map<String, String> values) {
        Builder builder = builder();
        values.forEach(builder::add);
        return builder.build();
    }

    /** First value of the header, or {@code null} when absent. */
    public String get(String name) {
        Entry entry = entries.get(key(name));
        return entry == null ? null : entry.values.get(0);
    }

    public String getOrDefault(String name, String defaultValue) {
        String value = get(name);
        return value == null ? defaultValue : value;
    }

    /** All values of the header in arrival order; empty when absent. */
    public List<String> getAll(String name) {
        Entry entry = entries.get(key(name));
        return entry == null ? List.of() : entry.values;
    }

    /**
     * Values of a FreeSWITCH array header ({@code ARRAY::a|:b|:c}). A plain value yields a single element list.
     */
    public List<String> getArray(String name) {
        String value = get(name);
        if (value == null) {
            return List.of();
        }
        if (!value.startsWith(ARRAY_PREFIX)) {
            return List.of(value);
        }
        List<String> items = new ArrayList<>();
        String rest = value.substring(ARRAY_PREFIX.length());
        int from = 0;
        int index;
        while ((index = rest.indexOf(ARRAY_SEPARATOR, from)) >= 0) {
            items.add(rest.substring(from, index));
            from = index + ARRAY_SEPARATOR.length();
        }
        items.add(rest.substring(from));
        return Collections.unmodifiableList(items);
    }

    /** Parses the header as an int, returning {@code defaultValue} when absent or malformed. */
    public int getInt(String name, int defaultValue) {
        String value = get(name);
        if (value == null) {
            return defaultValue;
        }
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    /** Parses the header as a long, returning {@code defaultValue} when absent or malformed. */
    public long getLong(String name, long defaultValue) {
        String value = get(name);
        if (value == null) {
            return defaultValue;
        }
        try {
            return Long.parseLong(value.trim());
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    public boolean contains(String name) {
        return entries.containsKey(key(name));
    }

    /** Header names as they were received, in arrival order. */
    public Set<String> names() {
        return entries.values().stream()
                .map(entry -> entry.name)
                .collect(Collectors.toCollection(java.util.LinkedHashSet::new));
    }

    public int size() {
        return entries.size();
    }

    public boolean isEmpty() {
        return entries.isEmpty();
    }

    /** Single-valued view (first value wins), keyed by the received header name, in arrival order. */
    public Map<String, String> toMap() {
        Map<String, String> map = new LinkedHashMap<>();
        entries.values().forEach(entry -> map.put(entry.name, entry.values.get(0)));
        return Collections.unmodifiableMap(map);
    }

    /** Returns a builder pre-populated with these headers. */
    public Builder toBuilder() {
        Builder builder = builder();
        entries.values().forEach(entry -> entry.values.forEach(value -> builder.add(entry.name, value)));
        return builder;
    }

    private static String key(String name) {
        return Objects.requireNonNull(name, "name").toLowerCase(Locale.ROOT);
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof EslHeaders other && entries.equals(other.entries);
    }

    @Override
    public int hashCode() {
        return entries.hashCode();
    }

    @Override
    public String toString() {
        return entries.values().stream()
                .map(entry -> entry.name + "=" + (entry.values.size() == 1 ? entry.values.get(0) : entry.values))
                .collect(Collectors.joining(", ", "{", "}"));
    }

    private record Entry(String name, List<String> values) {
    }

    /** Mutable builder; not thread-safe. */
    public static final class Builder {

        private final Map<String, String> names = new LinkedHashMap<>();
        private final Map<String, List<String>> values = new LinkedHashMap<>();

        private Builder() {
        }

        /** Appends a value, keeping any previous values of the same header. */
        public Builder add(String name, String value) {
            Objects.requireNonNull(value, "value");
            String key = key(name);
            names.putIfAbsent(key, name);
            values.computeIfAbsent(key, k -> new ArrayList<>(1)).add(value);
            return this;
        }

        /** Replaces all values of the header. */
        public Builder set(String name, String value) {
            remove(name);
            return add(name, value);
        }

        public Builder remove(String name) {
            String key = key(name);
            names.remove(key);
            values.remove(key);
            return this;
        }

        public EslHeaders build() {
            if (names.isEmpty()) {
                return EMPTY;
            }
            Map<String, Entry> entries = new LinkedHashMap<>(names.size() * 2);
            names.forEach((key, name) -> entries.put(key, new Entry(name, List.copyOf(values.get(key)))));
            return new EslHeaders(Collections.unmodifiableMap(entries));
        }
    }
}
