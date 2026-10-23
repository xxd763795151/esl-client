package io.github.xxd763795151.esl.test;

import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;

/**
 * One socket of {@link FakeFreeSwitch}: records the commands it receives, answers them with the default
 * FreeSWITCH behaviour (or a test supplied {@link CommandHandler}), and lets tests push frames.
 */
public final class FakePeer extends SimpleChannelInboundHandler<ReceivedCommand> {

    /** Custom command handling; return {@code true} when the command was handled. */
    @FunctionalInterface
    public interface CommandHandler {
        boolean handle(FakePeer peer, ReceivedCommand command);
    }

    private final FakeFreeSwitch owner;
    private final Map<String, String> channelData;
    private final List<ReceivedCommand> received = new CopyOnWriteArrayList<>();
    private final BlockingQueue<ReceivedCommand> queue = new LinkedBlockingQueue<>();
    private final CompletableFuture<Void> closed = new CompletableFuture<>();
    private final Map<String, String> executeVariables = new LinkedHashMap<>();
    private volatile Channel channel;
    private volatile CommandHandler handler;
    private volatile boolean ignoreExit;
    private volatile boolean autoCompleteExecute = true;

    FakePeer(FakeFreeSwitch owner, Map<String, String> channelData) {
        this.owner = owner;
        this.channelData = channelData;
    }

    // ------------------------------------------------------------------ test API

    public Channel channel() {
        return channel;
    }

    public FakePeer handler(CommandHandler handler) {
        this.handler = handler;
        return this;
    }

    /** Never answer {@code exit} and never close the socket on our own (a misbehaving peer). */
    public FakePeer ignoreExit(boolean ignoreExit) {
        this.ignoreExit = ignoreExit;
        return this;
    }

    /** Emit CHANNEL_EXECUTE_COMPLETE automatically after every sendmsg execute (default true). */
    public FakePeer autoCompleteExecute(boolean autoCompleteExecute) {
        this.autoCompleteExecute = autoCompleteExecute;
        return this;
    }

    /** Variables reported in CHANNEL_EXECUTE_COMPLETE events (as {@code variable_<name>} headers). */
    public FakePeer executeVariable(String name, String value) {
        executeVariables.put(name, value);
        return this;
    }

    public List<ReceivedCommand> received() {
        return List.copyOf(received);
    }

    public List<String> receivedLines() {
        List<String> lines = new ArrayList<>();
        received.forEach(command -> lines.add(command.line()));
        return lines;
    }

    public ReceivedCommand awaitCommand(Duration timeout) {
        return awaitCommand(command -> true, timeout);
    }

    /** Waits for the next received command matching {@code filter}, skipping others. */
    public ReceivedCommand awaitCommand(Predicate<ReceivedCommand> filter, Duration timeout) {
        long deadline = System.nanoTime() + timeout.toNanos();
        try {
            while (true) {
                long remaining = deadline - System.nanoTime();
                ReceivedCommand command = queue.poll(Math.max(0, remaining), TimeUnit.NANOSECONDS);
                if (command == null) {
                    throw new AssertionError("No matching command within " + timeout + "; received " + receivedLines());
                }
                if (filter.test(command)) {
                    return command;
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError(e);
        }
    }

    public CompletableFuture<Void> closeFuture() {
        return closed;
    }

    public boolean isOpen() {
        Channel ch = channel;
        return ch != null && ch.isActive();
    }

    public void send(String frame) {
        channel.writeAndFlush(Unpooled.copiedBuffer(frame, StandardCharsets.UTF_8));
    }

    public void reply(String replyText) {
        send("Content-Type: command/reply\nReply-Text: " + replyText + "\n\n");
    }

    public void reply(String replyText, Map<String, String> extraHeaders) {
        StringBuilder frame = new StringBuilder("Content-Type: command/reply\nReply-Text: ").append(replyText).append('\n');
        extraHeaders.forEach((name, value) -> frame.append(name).append(": ").append(value).append('\n'));
        send(frame.append('\n').toString());
    }

    public void apiResponse(String body) {
        send("Content-Type: api/response\nContent-Length: " + utf8Length(body) + "\n\n" + body);
    }

    /** Sends a {@code text/event-plain} event with URL-encoded header values. */
    public void event(Map<String, String> headers, String body) {
        StringBuilder text = new StringBuilder();
        headers.forEach((name, value) -> text.append(name).append(": ").append(encode(value)).append('\n'));
        if (body != null && !body.isEmpty()) {
            text.append("Content-Length: ").append(utf8Length(body)).append("\n\n").append(body);
        } else {
            text.append('\n');
        }
        String payload = text.toString();
        send("Content-Length: " + utf8Length(payload) + "\nContent-Type: text/event-plain\n\n" + payload);
    }

    public void event(String eventName, String uuid, Map<String, String> extra) {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Event-Name", eventName);
        if (uuid != null) {
            headers.put("Unique-ID", uuid);
        }
        headers.putAll(extra);
        event(headers, null);
    }

    public void disconnectNotice() {
        String body = "Disconnected, goodbye.\nSee you at ClueCon! http://www.cluecon.com/\n";
        send("Content-Type: text/disconnect-notice\nContent-Length: " + utf8Length(body) + "\n\n" + body);
    }

    public void close() {
        Channel ch = channel;
        if (ch != null) {
            ch.close();
        }
    }

    // ------------------------------------------------------------------ netty

    @Override
    public void channelActive(ChannelHandlerContext ctx) {
        channel = ctx.channel();
        ctx.channel().closeFuture().addListener(future -> closed.complete(null));
        owner.peerConnected(this);
        ctx.fireChannelActive();
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, ReceivedCommand command) {
        received.add(command);
        queue.add(command);
        CommandHandler custom = handler != null ? handler : owner.defaultHandler();
        if (custom != null && custom.handle(this, command)) {
            return;
        }
        respondDefault(command);
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        ctx.close();
    }

    /** What FreeSWITCH would answer. */
    public void respondDefault(ReceivedCommand command) {
        switch (command.name()) {
            case "auth" -> {
                if (command.argument().equals(owner.password())) {
                    reply("+OK accepted");
                } else {
                    reply("-ERR invalid");
                    channel.close();
                }
            }
            case "userauth" -> reply("+OK accepted");
            case "api" -> apiResponse(apiOutput(command.argument()));
            case "bgapi" -> {
                String jobUuid = command.header("Job-UUID");
                reply("+OK Job-UUID: " + jobUuid, Map.of("Job-UUID", jobUuid));
                event(Map.of("Event-Name", "BACKGROUND_JOB", "Job-UUID", jobUuid,
                        "Job-Command", command.argument()), "+OK " + command.argument() + "\n");
            }
            case "event" -> reply("+OK event listener enabled " + command.argument().split(" ")[0]);
            case "sendmsg" -> {
                reply("+OK");
                if (autoCompleteExecute && "execute".equalsIgnoreCase(command.header("call-command"))) {
                    completeExecute(command);
                }
            }
            case "connect" -> {
                Map<String, String> headers = new LinkedHashMap<>();
                headers.put("Socket-Mode", "async");
                headers.put("Control", "full");
                channelData.forEach((name, value) -> headers.put(name, encode(value)));
                reply("+OK", headers);
            }
            case "getvar" -> reply(channelData.getOrDefault("variable_" + command.argument(), "_undef_"));
            case "exit" -> {
                if (ignoreExit) {
                    return;
                }
                reply("+OK bye");
                disconnectNotice();
                channel.close();
            }
            case "nixevent", "noevents", "filter", "log", "nolog", "linger", "nolinger", "myevents",
                    "divert_events", "resume", "sendevent" -> reply("+OK");
            default -> reply("-ERR command not found");
        }
    }

    /** Emits CHANNEL_EXECUTE_COMPLETE for a {@code sendmsg execute} command. */
    public void completeExecute(ReceivedCommand sendmsg) {
        String uuid = sendmsg.argument().isEmpty() ? channelData.get("Unique-ID") : sendmsg.argument();
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Event-Name", "CHANNEL_EXECUTE_COMPLETE");
        headers.put("Unique-ID", uuid);
        headers.put("Application", sendmsg.header("execute-app-name"));
        String argument = sendmsg.header("execute-app-arg") != null ? sendmsg.header("execute-app-arg") : sendmsg.body();
        headers.put("Application-Data", argument == null ? "" : argument);
        headers.put("Application-Response", "_none_");
        if (sendmsg.header("Event-UUID") != null) {
            headers.put("Application-UUID", sendmsg.header("Event-UUID"));
        }
        executeVariables.forEach((name, value) -> headers.put("variable_" + name, value));
        event(headers, null);
    }

    private static String apiOutput(String argument) {
        if (argument.equals("version")) {
            return "FreeSWITCH Version 1.10.12-release (fake)\n";
        }
        if (argument.equals("status")) {
            return "UP 0 years, 0 days, 1 hour\nFreeSWITCH (Version 1.10.12) is ready\n";
        }
        if (argument.startsWith("echo ")) {
            return argument.substring(5);
        }
        return "+OK\n";
    }

    public static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private static int utf8Length(String text) {
        return text.getBytes(StandardCharsets.UTF_8).length;
    }
}
