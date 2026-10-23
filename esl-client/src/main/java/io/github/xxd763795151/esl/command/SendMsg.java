package io.github.xxd763795151.esl.command;

import io.github.xxd763795151.esl.protocol.EslHeaders;

/**
 * {@code sendmsg [uuid]}: controls a channel ({@code execute}, {@code hangup}, {@code unicast},
 * {@code nomedia}, {@code xferext} ...). The uuid may be omitted on outbound sockets, where it defaults to
 * the controlled channel.
 *
 * @param uuid    target channel, or {@code null} for the outbound session's own channel
 * @param headers message headers such as {@code call-command}
 * @param body    optional body; FreeSWITCH uses a {@code text/plain} body as the application argument
 */
public record SendMsg(String uuid, EslHeaders headers, String body) implements EslCommand {

    public SendMsg {
        if (uuid != null) {
            Wire.token(uuid, "uuid");
        }
        headers = Wire.headers(headers);
        if (body != null && !headers.contains("content-type")) {
            headers = headers.toBuilder().add("content-type", "text/plain").build();
        }
    }

    public static Builder builder(String uuid) {
        return new Builder(uuid);
    }

    /** {@code call-command: hangup} with an optional hangup cause such as {@code NORMAL_CLEARING}. */
    public static SendMsg hangup(String uuid, String cause) {
        Builder builder = builder(uuid).header("call-command", "hangup");
        if (cause != null) {
            builder.header("hangup-cause", cause);
        }
        return builder.build();
    }

    /** {@code call-command: nomedia}: take the media path of the channel off FreeSWITCH. */
    public static SendMsg noMedia(String uuid, String info) {
        Builder builder = builder(uuid).header("call-command", "nomedia");
        if (info != null) {
            builder.header("nomedia-uuid", info);
        }
        return builder.build();
    }

    /**
     * {@code call-command: unicast}: mirror the channel's media to a UDP/TCP endpoint.
     */
    public static SendMsg unicast(String uuid, String localIp, int localPort, String remoteIp, int remotePort,
                                  String transport, boolean nativeMedia) {
        Builder builder = builder(uuid)
                .header("call-command", "unicast")
                .header("local-ip", localIp)
                .header("local-port", Integer.toString(localPort))
                .header("remote-ip", remoteIp)
                .header("remote-port", Integer.toString(remotePort))
                .header("transport", transport);
        if (nativeMedia) {
            builder.header("flags", "native");
        }
        return builder.build();
    }

    @Override
    public String commandLine() {
        return uuid == null ? "sendmsg" : "sendmsg " + uuid;
    }

    public static final class Builder {
        private final String uuid;
        private final EslHeaders.Builder headers = EslHeaders.builder();
        private String body;

        private Builder(String uuid) {
            this.uuid = uuid;
        }

        public Builder header(String name, String value) {
            headers.add(name, value);
            return this;
        }

        public Builder body(String body) {
            this.body = body;
            return this;
        }

        public SendMsg build() {
            return new SendMsg(uuid, headers.build(), body);
        }
    }
}
