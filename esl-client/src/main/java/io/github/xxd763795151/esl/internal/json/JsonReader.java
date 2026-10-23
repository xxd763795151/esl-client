package io.github.xxd763795151.esl.internal.json;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Small, strict JSON reader (RFC 8259) used for {@code text/event-json}, so the core library needs no JSON
 * dependency. Produces {@link Map} (insertion ordered), {@link List}, {@link String}, {@link Boolean},
 * {@code null}, and numbers kept as their literal {@link String} text (FreeSWITCH only sends strings anyway).
 */
public final class JsonReader {

    private static final int MAX_DEPTH = 64;

    private final String text;
    private int pos;
    private int depth;

    private JsonReader(String text) {
        this.text = text;
    }

    public static Object parse(String text) {
        JsonReader reader = new JsonReader(text);
        reader.skipWhitespace();
        Object value = reader.readValue();
        reader.skipWhitespace();
        if (reader.pos != text.length()) {
            throw reader.error("trailing characters");
        }
        return value;
    }

    private Object readValue() {
        if (pos >= text.length()) {
            throw error("unexpected end of input");
        }
        char c = text.charAt(pos);
        return switch (c) {
            case '{' -> readObject();
            case '[' -> readArray();
            case '"' -> readString();
            case 't' -> literal("true", Boolean.TRUE);
            case 'f' -> literal("false", Boolean.FALSE);
            case 'n' -> literal("null", null);
            default -> {
                if (c == '-' || (c >= '0' && c <= '9')) {
                    yield readNumber();
                }
                throw error("unexpected character '" + c + "'");
            }
        };
    }

    private Map<String, Object> readObject() {
        enter();
        pos++; // {
        Map<String, Object> map = new LinkedHashMap<>();
        skipWhitespace();
        if (peek() == '}') {
            pos++;
            depth--;
            return map;
        }
        while (true) {
            skipWhitespace();
            if (peek() != '"') {
                throw error("expected object key");
            }
            String key = readString();
            skipWhitespace();
            expect(':');
            skipWhitespace();
            map.put(key, readValue());
            skipWhitespace();
            char c = next();
            if (c == '}') {
                depth--;
                return map;
            }
            if (c != ',') {
                throw error("expected ',' or '}'");
            }
        }
    }

    private List<Object> readArray() {
        enter();
        pos++; // [
        List<Object> list = new ArrayList<>();
        skipWhitespace();
        if (peek() == ']') {
            pos++;
            depth--;
            return list;
        }
        while (true) {
            skipWhitespace();
            list.add(readValue());
            skipWhitespace();
            char c = next();
            if (c == ']') {
                depth--;
                return list;
            }
            if (c != ',') {
                throw error("expected ',' or ']'");
            }
        }
    }

    private String readString() {
        pos++; // opening quote
        StringBuilder out = null;
        int start = pos;
        while (true) {
            if (pos >= text.length()) {
                throw error("unterminated string");
            }
            char c = text.charAt(pos);
            if (c == '"') {
                String value = out == null ? text.substring(start, pos) : out.append(text, start, pos).toString();
                pos++;
                return value;
            }
            if (c == '\\') {
                if (out == null) {
                    out = new StringBuilder();
                }
                out.append(text, start, pos);
                pos++;
                out.append(readEscape());
                start = pos;
            } else {
                if (c < 0x20) {
                    throw error("control character in string");
                }
                pos++;
            }
        }
    }

    private char readEscape() {
        char c = next();
        return switch (c) {
            case '"' -> '"';
            case '\\' -> '\\';
            case '/' -> '/';
            case 'b' -> '\b';
            case 'f' -> '\f';
            case 'n' -> '\n';
            case 'r' -> '\r';
            case 't' -> '\t';
            case 'u' -> {
                if (pos + 4 > text.length()) {
                    throw error("truncated unicode escape");
                }
                try {
                    char decoded = (char) Integer.parseInt(text.substring(pos, pos + 4), 16);
                    pos += 4;
                    yield decoded;
                } catch (NumberFormatException e) {
                    throw error("invalid unicode escape");
                }
            }
            default -> throw error("invalid escape '\\" + c + "'");
        };
    }

    private String readNumber() {
        int start = pos;
        if (peek() == '-') {
            pos++;
        }
        while (pos < text.length()) {
            char c = text.charAt(pos);
            if ((c >= '0' && c <= '9') || c == '.' || c == 'e' || c == 'E' || c == '+' || c == '-') {
                pos++;
            } else {
                break;
            }
        }
        String number = text.substring(start, pos);
        try {
            Double.parseDouble(number);
        } catch (NumberFormatException e) {
            throw error("invalid number '" + number + "'");
        }
        return number;
    }

    private Object literal(String word, Object value) {
        if (!text.startsWith(word, pos)) {
            throw error("invalid literal");
        }
        pos += word.length();
        return value;
    }

    private void enter() {
        if (++depth > MAX_DEPTH) {
            throw error("nesting too deep");
        }
    }

    private void skipWhitespace() {
        while (pos < text.length()) {
            char c = text.charAt(pos);
            if (c == ' ' || c == '\t' || c == '\n' || c == '\r') {
                pos++;
            } else {
                return;
            }
        }
    }

    private char peek() {
        if (pos >= text.length()) {
            throw error("unexpected end of input");
        }
        return text.charAt(pos);
    }

    private char next() {
        char c = peek();
        pos++;
        return c;
    }

    private void expect(char expected) {
        if (next() != expected) {
            throw error("expected '" + expected + "'");
        }
    }

    private IllegalArgumentException error(String message) {
        return new IllegalArgumentException("Invalid JSON at offset " + pos + ": " + message);
    }
}
