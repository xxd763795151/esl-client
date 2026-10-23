package io.github.xxd763795151.esl.event;

/**
 * Names of commonly used FreeSWITCH events. Subscribing accepts any name, these are just constants.
 */
public final class EventNames {

    public static final String ALL = "ALL";
    public static final String CUSTOM = "CUSTOM";
    public static final String HEARTBEAT = "HEARTBEAT";
    public static final String BACKGROUND_JOB = "BACKGROUND_JOB";
    public static final String API = "API";
    public static final String RE_SCHEDULE = "RE_SCHEDULE";
    public static final String SHUTDOWN = "SHUTDOWN";
    public static final String STARTUP = "STARTUP";

    public static final String CHANNEL_CREATE = "CHANNEL_CREATE";
    public static final String CHANNEL_DESTROY = "CHANNEL_DESTROY";
    public static final String CHANNEL_STATE = "CHANNEL_STATE";
    public static final String CHANNEL_CALLSTATE = "CHANNEL_CALLSTATE";
    public static final String CHANNEL_ANSWER = "CHANNEL_ANSWER";
    public static final String CHANNEL_PROGRESS = "CHANNEL_PROGRESS";
    public static final String CHANNEL_PROGRESS_MEDIA = "CHANNEL_PROGRESS_MEDIA";
    public static final String CHANNEL_BRIDGE = "CHANNEL_BRIDGE";
    public static final String CHANNEL_UNBRIDGE = "CHANNEL_UNBRIDGE";
    public static final String CHANNEL_HOLD = "CHANNEL_HOLD";
    public static final String CHANNEL_UNHOLD = "CHANNEL_UNHOLD";
    public static final String CHANNEL_PARK = "CHANNEL_PARK";
    public static final String CHANNEL_UNPARK = "CHANNEL_UNPARK";
    public static final String CHANNEL_EXECUTE = "CHANNEL_EXECUTE";
    public static final String CHANNEL_EXECUTE_COMPLETE = "CHANNEL_EXECUTE_COMPLETE";
    public static final String CHANNEL_HANGUP = "CHANNEL_HANGUP";
    public static final String CHANNEL_HANGUP_COMPLETE = "CHANNEL_HANGUP_COMPLETE";
    public static final String CHANNEL_ORIGINATE = "CHANNEL_ORIGINATE";
    public static final String CHANNEL_OUTGOING = "CHANNEL_OUTGOING";
    public static final String CHANNEL_DATA = "CHANNEL_DATA";
    public static final String CHANNEL_APPLICATION = "CHANNEL_APPLICATION";
    public static final String CALL_UPDATE = "CALL_UPDATE";
    public static final String DTMF = "DTMF";
    public static final String DETECTED_SPEECH = "DETECTED_SPEECH";
    public static final String DETECTED_TONE = "DETECTED_TONE";
    public static final String PLAYBACK_START = "PLAYBACK_START";
    public static final String PLAYBACK_STOP = "PLAYBACK_STOP";
    public static final String RECORD_START = "RECORD_START";
    public static final String RECORD_STOP = "RECORD_STOP";
    public static final String MEDIA_BUG_START = "MEDIA_BUG_START";
    public static final String MEDIA_BUG_STOP = "MEDIA_BUG_STOP";
    public static final String CODEC = "CODEC";
    public static final String PRESENCE_IN = "PRESENCE_IN";
    public static final String MESSAGE_WAITING = "MESSAGE_WAITING";
    public static final String NOTIFY = "NOTIFY";
    public static final String SEND_MESSAGE = "SEND_MESSAGE";
    public static final String RECV_MESSAGE = "RECV_MESSAGE";
    public static final String CONFERENCE_DATA = "CONFERENCE_DATA";

    private EventNames() {
    }
}
