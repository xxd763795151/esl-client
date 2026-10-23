package io.github.xxd763795151.esl.protocol;

/**
 * Content types FreeSWITCH {@code mod_event_socket} sends to its peers.
 */
public final class ContentTypes {

    public static final String AUTH_REQUEST = "auth/request";
    public static final String COMMAND_REPLY = "command/reply";
    public static final String API_RESPONSE = "api/response";
    public static final String EVENT_PLAIN = "text/event-plain";
    public static final String EVENT_JSON = "text/event-json";
    public static final String EVENT_XML = "text/event-xml";
    public static final String LOG_DATA = "log/data";
    public static final String DISCONNECT_NOTICE = "text/disconnect-notice";
    public static final String RUDE_REJECTION = "text/rude-rejection";

    private ContentTypes() {
    }
}
