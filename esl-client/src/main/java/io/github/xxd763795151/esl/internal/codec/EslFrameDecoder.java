package io.github.xxd763795151.esl.internal.codec;

import io.github.xxd763795151.esl.protocol.EslHeaders;
import io.github.xxd763795151.esl.protocol.EslMessage;
import io.github.xxd763795151.esl.protocol.HeaderNames;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.ByteToMessageDecoder;
import io.netty.handler.codec.CorruptedFrameException;
import io.netty.handler.codec.TooLongFrameException;

import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Splits the inbound byte stream into {@link EslMessage} frames.
 * <p>
 * A frame is a block of {@code Name: value} lines terminated by an empty line, optionally followed by
 * exactly {@code Content-Length} bytes of body. Everything is decoded as UTF-8; unknown headers are kept,
 * never rejected. The decoder keeps a scan offset so partially received header blocks are not rescanned.
 */
public final class EslFrameDecoder extends ByteToMessageDecoder {

    private static final byte LF = '\n';

    private final int maxHeaderBytes;
    private final int maxBodyBytes;

    /** Headers of a frame whose body has not fully arrived yet. */
    private EslHeaders pendingHeaders;
    private int pendingBodyLength;
    /** Offset (relative to readerIndex) from which to continue looking for the header terminator. */
    private int scanOffset;

    public EslFrameDecoder(int maxHeaderBytes, int maxBodyBytes) {
        if (maxHeaderBytes <= 0 || maxBodyBytes <= 0) {
            throw new IllegalArgumentException("limits must be positive");
        }
        this.maxHeaderBytes = maxHeaderBytes;
        this.maxBodyBytes = maxBodyBytes;
    }

    @Override
    protected void decode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) {
        while (in.isReadable()) {
            if (pendingHeaders == null) {
                skipLeadingLineFeeds(in);
                int headerLength = findHeaderEnd(in);
                if (headerLength < 0) {
                    return;
                }
                String headerBlock = in.toString(in.readerIndex(), headerLength, StandardCharsets.UTF_8);
                in.skipBytes(headerLength + 2);
                scanOffset = 0;
                EslHeaders headers = parseHeaders(headerBlock);
                int contentLength = contentLength(headers);
                if (contentLength == 0) {
                    out.add(new EslMessage(headers, ""));
                    continue;
                }
                pendingHeaders = headers;
                pendingBodyLength = contentLength;
            }
            if (in.readableBytes() < pendingBodyLength) {
                return;
            }
            String body = in.readCharSequence(pendingBodyLength, StandardCharsets.UTF_8).toString();
            out.add(new EslMessage(pendingHeaders, body));
            pendingHeaders = null;
            pendingBodyLength = 0;
        }
    }

    private static void skipLeadingLineFeeds(ByteBuf in) {
        while (in.isReadable() && (in.getByte(in.readerIndex()) == LF || in.getByte(in.readerIndex()) == '\r')) {
            in.skipBytes(1);
        }
    }

    /**
     * Returns the length of the header block (excluding the terminating blank line) or -1 if incomplete.
     */
    private int findHeaderEnd(ByteBuf in) {
        int start = in.readerIndex();
        int end = in.writerIndex();
        int from = start + scanOffset;
        while (true) {
            int lf = in.indexOf(from, end, LF);
            if (lf < 0 || lf + 1 >= end) {
                // resume from the unchecked line feed (or the end) once more bytes arrive
                scanOffset = (lf < 0 ? end : lf) - start;
                checkHeaderSize(end - start);
                return -1;
            }
            if (in.getByte(lf + 1) == LF) {
                return checkHeaderSize(lf - start);
            }
            from = lf + 1;
        }
    }

    private int checkHeaderSize(int size) {
        if (size > maxHeaderBytes) {
            throw new TooLongFrameException("ESL header block exceeds " + maxHeaderBytes + " bytes");
        }
        return size;
    }

    private int contentLength(EslHeaders headers) {
        String value = headers.get(HeaderNames.CONTENT_LENGTH);
        if (value == null) {
            return 0;
        }
        int length;
        try {
            length = Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            throw new CorruptedFrameException("Invalid Content-Length: " + value);
        }
        if (length < 0) {
            throw new CorruptedFrameException("Negative Content-Length: " + length);
        }
        if (length > maxBodyBytes) {
            throw new TooLongFrameException("ESL body of " + length + " bytes exceeds " + maxBodyBytes);
        }
        return length;
    }

    static EslHeaders parseHeaders(String block) {
        EslHeaders.Builder builder = EslHeaders.builder();
        int from = 0;
        int length = block.length();
        while (from < length) {
            int lineEnd = block.indexOf('\n', from);
            if (lineEnd < 0) {
                lineEnd = length;
            }
            int contentEnd = lineEnd > from && block.charAt(lineEnd - 1) == '\r' ? lineEnd - 1 : lineEnd;
            int colon = block.indexOf(':', from);
            if (colon > from && colon < contentEnd) {
                String name = block.substring(from, colon).trim();
                int valueStart = colon + 1;
                while (valueStart < contentEnd && block.charAt(valueStart) == ' ') {
                    valueStart++;
                }
                if (!name.isEmpty()) {
                    builder.add(name, block.substring(valueStart, contentEnd));
                }
            }
            from = lineEnd + 1;
        }
        return builder.build();
    }

    @Override
    protected void handlerRemoved0(ChannelHandlerContext ctx) {
        pendingHeaders = null;
    }
}
