package io.github.xxd763795151.esl.command;

import io.github.xxd763795151.esl.protocol.EslHeaders;

import java.util.Collection;
import java.util.Objects;

/**
 * Validation shared by command types. A line break inside a command line or header would let a caller
 * smuggle additional commands into the socket, so it is rejected up front.
 */
final class Wire {

    private Wire() {
    }

    static String token(String value, String what) {
        String text = line(value, what);
        if (text.isBlank() || text.indexOf(' ') >= 0) {
            throw new IllegalArgumentException(what + " must be a single non-blank word: '" + text + "'");
        }
        return text;
    }

    static String line(String value, String what) {
        Objects.requireNonNull(value, what);
        if (value.indexOf('\n') >= 0 || value.indexOf('\r') >= 0) {
            throw new IllegalArgumentException(what + " must not contain line breaks");
        }
        return value;
    }

    static String optionalLine(String value, String what) {
        return value == null ? null : line(value, what);
    }

    static <C extends Collection<String>> C words(C values, String what) {
        Objects.requireNonNull(values, what);
        if (values.isEmpty()) {
            throw new IllegalArgumentException(what + " must not be empty");
        }
        values.forEach(value -> line(value, what));
        return values;
    }

    static EslHeaders headers(EslHeaders headers) {
        if (headers == null) {
            return EslHeaders.empty();
        }
        for (String name : headers.names()) {
            line(name, "header name");
            if (name.indexOf(':') >= 0 || name.isBlank()) {
                throw new IllegalArgumentException("invalid header name: '" + name + "'");
            }
            headers.getAll(name).forEach(value -> line(value, "header " + name));
        }
        return headers;
    }

    static String join(String first, String rest) {
        return rest == null || rest.isEmpty() ? first : first + ' ' + rest;
    }
}
