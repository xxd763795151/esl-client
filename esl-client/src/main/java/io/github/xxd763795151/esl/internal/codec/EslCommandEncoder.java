package io.github.xxd763795151.esl.internal.codec;

import io.github.xxd763795151.esl.command.EslCommand;
import io.github.xxd763795151.esl.protocol.EslHeaders;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.MessageToByteEncoder;

/**
 * Serialises an {@link EslCommand} as UTF-8: command line, headers, optional body, blank line terminator.
 */
@ChannelHandler.Sharable
public final class EslCommandEncoder extends MessageToByteEncoder<EslCommand> {

    public static final EslCommandEncoder INSTANCE = new EslCommandEncoder();

    private EslCommandEncoder() {
        super(EslCommand.class);
    }

    @Override
    protected void encode(ChannelHandlerContext ctx, EslCommand command, ByteBuf out) {
        encode(command, out);
    }

    public static void encode(EslCommand command, ByteBuf out) {
        ByteBufUtil.writeUtf8(out, command.commandLine());
        out.writeByte('\n');
        EslHeaders headers = command.headers();
        for (String name : headers.names()) {
            for (String value : headers.getAll(name)) {
                ByteBufUtil.writeUtf8(out, name);
                out.writeByte(':').writeByte(' ');
                ByteBufUtil.writeUtf8(out, value);
                out.writeByte('\n');
            }
        }
        String body = command.body();
        if (body == null) {
            out.writeByte('\n');
            return;
        }
        int length = ByteBufUtil.utf8Bytes(body);
        ByteBufUtil.writeAscii(out, "Content-Length: " + length + "\n\n");
        ByteBufUtil.writeUtf8(out, body);
    }
}
