package io.github.xxd763795151.esl.event;

import io.github.xxd763795151.esl.internal.json.JsonReader;
import io.github.xxd763795151.esl.protocol.EslHeaders;
import io.github.xxd763795151.esl.protocol.EslMessage;
import io.github.xxd763795151.esl.protocol.HeaderNames;

import javax.xml.XMLConstants;
import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/**
 * Built-in parsers for the three event formats. None of them needs a third party library.
 */
public final class EventParsers {

    private static final String JSON_BODY_KEY = "_body";
    private static final XMLInputFactory XML_FACTORY = createXmlFactory();

    private EventParsers() {
    }

    public static EventParser forFormat(EventFormat format) {
        return switch (format) {
            case PLAIN -> EventParsers::parsePlain;
            case JSON -> EventParsers::parseJson;
            case XML -> EventParsers::parseXml;
        };
    }

    /**
     * Parses an event frame based on its content type.
     *
     * @throws IllegalArgumentException when the frame is not an event or cannot be parsed
     */
    public static EslEvent parse(EslMessage message) {
        EventFormat format = EventFormat.fromContentType(message.contentType());
        if (format == null) {
            throw new IllegalArgumentException("Not an event frame: " + message.contentType());
        }
        return forFormat(format).parse(message);
    }

    /**
     * Builds an event from percent-encoded headers, as found in the reply to the outbound {@code connect}
     * command.
     */
    public static EslEvent fromEncodedHeaders(EslHeaders encoded, String body) {
        EslHeaders.Builder builder = EslHeaders.builder();
        for (String name : encoded.names()) {
            for (String value : encoded.getAll(name)) {
                builder.add(name, PercentDecoder.decode(value));
            }
        }
        return new EslEvent(builder.build(), body, EventFormat.PLAIN);
    }

    // ------------------------------------------------------------------ plain

    static EslEvent parsePlain(EslMessage message) {
        String text = message.body();
        int separator = text.indexOf("\n\n");
        String headerBlock = separator < 0 ? text : text.substring(0, separator);
        String body = separator < 0 ? "" : text.substring(separator + 2);

        EslHeaders.Builder headers = EslHeaders.builder();
        int from = 0;
        int length = headerBlock.length();
        while (from < length) {
            int lineEnd = headerBlock.indexOf('\n', from);
            if (lineEnd < 0) {
                lineEnd = length;
            }
            int colon = headerBlock.indexOf(':', from);
            if (colon > from && colon < lineEnd) {
                int valueStart = colon + 1;
                if (valueStart < lineEnd && headerBlock.charAt(valueStart) == ' ') {
                    valueStart++;
                }
                int valueEnd = lineEnd > valueStart && headerBlock.charAt(lineEnd - 1) == '\r' ? lineEnd - 1 : lineEnd;
                headers.add(headerBlock.substring(from, colon).trim(),
                        PercentDecoder.decode(headerBlock.substring(valueStart, valueEnd)));
            }
            from = lineEnd + 1;
        }
        EslHeaders parsed = headers.build();
        return new EslEvent(parsed, trimBody(body, parsed), EventFormat.PLAIN);
    }

    /** Honour the event's own Content-Length (bytes) when FreeSWITCH appended anything after the body. */
    private static String trimBody(String body, EslHeaders headers) {
        int declared = headers.getInt(HeaderNames.CONTENT_LENGTH, -1);
        if (declared < 0 || body.length() <= declared / 4) {
            return body;
        }
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        return bytes.length > declared ? new String(bytes, 0, declared, StandardCharsets.UTF_8) : body;
    }

    // ------------------------------------------------------------------ json

    static EslEvent parseJson(EslMessage message) {
        Object root = JsonReader.parse(message.body());
        if (!(root instanceof Map<?, ?> map)) {
            throw new IllegalArgumentException("JSON event is not an object");
        }
        EslHeaders.Builder headers = EslHeaders.builder();
        String body = "";
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            String name = String.valueOf(entry.getKey());
            Object value = entry.getValue();
            if (JSON_BODY_KEY.equals(name)) {
                body = value == null ? "" : value.toString();
            } else if (value instanceof List<?> list) {
                list.forEach(item -> headers.add(name, String.valueOf(item)));
            } else if (value != null) {
                headers.add(name, value.toString());
            }
        }
        return new EslEvent(headers.build(), body, EventFormat.JSON);
    }

    // ------------------------------------------------------------------ xml

    static EslEvent parseXml(EslMessage message) {
        EslHeaders.Builder headers = EslHeaders.builder();
        String body = "";
        XMLStreamReader reader = null;
        try {
            reader = XML_FACTORY.createXMLStreamReader(new StringReader(message.body()));
            boolean inHeaders = false;
            while (reader.hasNext()) {
                int type = reader.next();
                if (type == XMLStreamConstants.START_ELEMENT) {
                    String element = reader.getLocalName();
                    if ("headers".equals(element)) {
                        inHeaders = true;
                    } else if (inHeaders) {
                        readXmlHeader(reader, element, headers);
                    } else if ("body".equals(element)) {
                        body = reader.getElementText();
                    }
                } else if (type == XMLStreamConstants.END_ELEMENT && "headers".equals(reader.getLocalName())) {
                    inHeaders = false;
                }
            }
        } catch (XMLStreamException e) {
            throw new IllegalArgumentException("Invalid XML event: " + e.getMessage(), e);
        } finally {
            if (reader != null) {
                try {
                    reader.close();
                } catch (XMLStreamException ignored) {
                    // nothing to release for an in-memory reader
                }
            }
        }
        return new EslEvent(headers.build(), body, EventFormat.XML);
    }

    /** A header element holds either text, or nested elements (array headers) whose texts are the values. */
    private static void readXmlHeader(XMLStreamReader reader, String name, EslHeaders.Builder headers)
            throws XMLStreamException {
        StringBuilder text = new StringBuilder();
        boolean nested = false;
        int depth = 1;
        while (depth > 0 && reader.hasNext()) {
            int type = reader.next();
            switch (type) {
                case XMLStreamConstants.START_ELEMENT -> {
                    depth++;
                    nested = true;
                    headers.add(name, reader.getElementText());
                    depth--;
                }
                case XMLStreamConstants.CHARACTERS, XMLStreamConstants.CDATA -> text.append(reader.getText());
                case XMLStreamConstants.END_ELEMENT -> depth--;
                default -> {
                    // comments and processing instructions carry no header data
                }
            }
        }
        if (!nested) {
            headers.add(name, text.toString());
        }
    }

    private static XMLInputFactory createXmlFactory() {
        XMLInputFactory factory = XMLInputFactory.newFactory();
        // events come from the network: never resolve DTDs or external entities (XXE)
        factory.setProperty(XMLInputFactory.SUPPORT_DTD, false);
        factory.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);
        factory.setProperty(XMLInputFactory.IS_COALESCING, true);
        try {
            factory.setProperty(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        } catch (IllegalArgumentException ignored) {
            // property not supported by this StAX implementation; DTD support is already disabled
        }
        return factory;
    }
}
