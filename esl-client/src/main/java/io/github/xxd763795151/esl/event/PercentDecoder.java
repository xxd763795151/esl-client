package io.github.xxd763795151.esl.event;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

/**
 * Decodes the {@code %XX} escapes FreeSWITCH uses in plain event headers.
 * <p>
 * Unlike {@link java.net.URLDecoder} this does not turn {@code +} into a space (FreeSWITCH escapes spaces as
 * {@code %20}, and {@code +} legitimately appears in E.164 numbers) and keeps malformed escapes verbatim
 * instead of failing the whole event.
 */
public final class PercentDecoder {

    private PercentDecoder() {
    }

    public static String decode(String value) {
        int first = value.indexOf('%');
        if (first < 0) {
            return value;
        }
        int length = value.length();
        StringBuilder out = new StringBuilder(length);
        out.append(value, 0, first);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        int i = first;
        while (i < length) {
            if (isEscape(value, i)) {
                // decode a run of escapes together so multi-byte UTF-8 sequences are kept intact
                bytes.reset();
                while (isEscape(value, i)) {
                    bytes.write((hex(value.charAt(i + 1)) << 4) | hex(value.charAt(i + 2)));
                    i += 3;
                }
                out.append(new String(bytes.toByteArray(), StandardCharsets.UTF_8));
            } else {
                out.append(value.charAt(i++));
            }
        }
        return out.toString();
    }

    private static boolean isEscape(String value, int index) {
        return index + 2 < value.length()
                && value.charAt(index) == '%'
                && isHex(value.charAt(index + 1))
                && isHex(value.charAt(index + 2));
    }

    private static boolean isHex(char c) {
        return (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F');
    }

    private static int hex(char c) {
        return c <= '9' ? c - '0' : (c | 0x20) - 'a' + 10;
    }
}
