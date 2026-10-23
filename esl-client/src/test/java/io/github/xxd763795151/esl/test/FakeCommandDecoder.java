package io.github.xxd763795151.esl.test;

import io.github.xxd763795151.esl.protocol.EslHeaders;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.ByteToMessageDecoder;

import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Decodes commands the way mod_event_socket reads them: lines up to a blank line, then an optional
 * Content-Length body.
 */
final class FakeCommandDecoder extends ByteToMessageDecoder {

    private String pendingLine;
    private EslHeaders pendingHeaders;
    private int pendingLength;

    @Override
    protected void decode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) {
        while (in.isReadable()) {
            if (pendingLine == null) {
                int end = indexOfBlankLine(in);
                if (end < 0) {
                    return;
                }
                String block = in.toString(in.readerIndex(), end - in.readerIndex(), StandardCharsets.UTF_8);
                in.readerIndex(end + 2);
                String[] lines = block.split("\n");
                EslHeaders.Builder headers = EslHeaders.builder();
                for (int i = 1; i < lines.length; i++) {
                    int colon = lines[i].indexOf(':');
                    if (colon > 0) {
                        headers.add(lines[i].substring(0, colon).trim(), lines[i].substring(colon + 1).trim());
                    }
                }
                pendingLine = lines[0].trim();
                pendingHeaders = headers.build();
                pendingLength = pendingHeaders.getInt("Content-Length", 0);
            }
            if (in.readableBytes() < pendingLength) {
                return;
            }
            String body = in.readCharSequence(pendingLength, StandardCharsets.UTF_8).toString();
            out.add(new ReceivedCommand(pendingLine, pendingHeaders, body));
            pendingLine = null;
            pendingHeaders = null;
            pendingLength = 0;
        }
    }

    private static int indexOfBlankLine(ByteBuf in) {
        for (int i = in.readerIndex(); i < in.writerIndex() - 1; i++) {
            if (in.getByte(i) == '\n' && in.getByte(i + 1) == '\n') {
                return i;
            }
        }
        return -1;
    }
}
