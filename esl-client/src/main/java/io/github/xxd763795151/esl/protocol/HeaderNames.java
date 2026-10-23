package io.github.xxd763795151.esl.protocol;

/**
 * Well known header names used by the event socket protocol and FreeSWITCH events.
 */
public final class HeaderNames {

    // ---- frame level ----
    public static final String CONTENT_TYPE = "Content-Type";
    public static final String CONTENT_LENGTH = "Content-Length";
    public static final String CONTENT_DISPOSITION = "Content-Disposition";
    public static final String REPLY_TEXT = "Reply-Text";
    public static final String JOB_UUID = "Job-UUID";
    public static final String SOCKET_MODE = "Socket-Mode";
    public static final String CONTROL = "Control";
    public static final String CONTROLLED_SESSION_UUID = "Controlled-Session-UUID";

    // ---- log/data ----
    public static final String LOG_LEVEL = "Log-Level";
    public static final String TEXT_CHANNEL = "Text-Channel";
    public static final String LOG_FILE = "Log-File";
    public static final String LOG_FUNC = "Log-Func";
    public static final String LOG_LINE = "Log-Line";
    public static final String USER_DATA = "User-Data";

    // ---- events ----
    public static final String EVENT_NAME = "Event-Name";
    public static final String EVENT_SUBCLASS = "Event-Subclass";
    public static final String EVENT_SEQUENCE = "Event-Sequence";
    public static final String EVENT_DATE_TIMESTAMP = "Event-Date-Timestamp";
    public static final String CORE_UUID = "Core-UUID";
    public static final String FREESWITCH_HOSTNAME = "FreeSWITCH-Hostname";
    public static final String UNIQUE_ID = "Unique-ID";
    public static final String CHANNEL_CALL_UUID = "Channel-Call-UUID";
    public static final String CHANNEL_NAME = "Channel-Name";
    public static final String CHANNEL_STATE = "Channel-State";
    public static final String ANSWER_STATE = "Answer-State";
    public static final String CALL_DIRECTION = "Call-Direction";
    public static final String CALLER_CALLER_ID_NAME = "Caller-Caller-ID-Name";
    public static final String CALLER_CALLER_ID_NUMBER = "Caller-Caller-ID-Number";
    public static final String CALLER_DESTINATION_NUMBER = "Caller-Destination-Number";
    public static final String HANGUP_CAUSE = "Hangup-Cause";
    public static final String APPLICATION = "Application";
    public static final String APPLICATION_DATA = "Application-Data";
    public static final String APPLICATION_RESPONSE = "Application-Response";
    public static final String APPLICATION_UUID = "Application-UUID";
    public static final String JOB_COMMAND = "Job-Command";
    public static final String JOB_COMMAND_ARG = "Job-Command-Arg";
    public static final String DTMF_DIGIT = "DTMF-Digit";
    public static final String VARIABLE_PREFIX = "variable_";

    private HeaderNames() {
    }
}
