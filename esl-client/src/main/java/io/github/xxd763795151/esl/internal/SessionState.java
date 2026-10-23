package io.github.xxd763795151.esl.internal;

import io.github.xxd763795151.esl.command.EslCommand;
import io.github.xxd763795151.esl.command.LogLevel;
import io.github.xxd763795151.esl.event.EventFormat;
import io.github.xxd763795151.esl.event.EventNames;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Server-side state FreeSWITCH keeps per socket (subscriptions, filters, log level), mirrored from
 * successfully acknowledged commands. Used to subscribe on demand and to restore the state after a reconnect.
 */
public final class SessionState {

    private final Set<String> events = new LinkedHashSet<>();
    private final Set<String> customSubclasses = new LinkedHashSet<>();
    private final Set<Map.Entry<String, String>> filters = new LinkedHashSet<>();
    private EventFormat format;
    private LogLevel logLevel;
    private boolean myEvents;

    /** Records the effect of a command FreeSWITCH acknowledged with {@code +OK}. */
    public synchronized void record(EslCommand command) {
        if (command instanceof EslCommand.Event event) {
            format = event.format();
            addEventTokens(event.events(), true);
        } else if (command instanceof EslCommand.NixEvent nix) {
            addEventTokens(nix.events(), false);
        } else if (command instanceof EslCommand.NoEvents) {
            events.clear();
            customSubclasses.clear();
        } else if (command instanceof EslCommand.Filter filter) {
            filters.add(Map.entry(filter.header(), filter.value()));
        } else if (command instanceof EslCommand.FilterDelete delete) {
            if (delete.value() == null && "all".equalsIgnoreCase(delete.header())) {
                filters.clear();
            } else if (delete.value() == null) {
                filters.removeIf(entry -> entry.getKey().equalsIgnoreCase(delete.header()));
            } else {
                filters.remove(Map.entry(delete.header(), delete.value()));
            }
        } else if (command instanceof EslCommand.Log log) {
            logLevel = log.level();
        } else if (command instanceof EslCommand.NoLog) {
            logLevel = null;
        } else if (command instanceof EslCommand.MyEvents) {
            myEvents = true;
        }
    }

    /**
     * FreeSWITCH treats every token after {@code CUSTOM} as a subclass, which is why subclasses are tracked
     * separately and always replayed last.
     */
    private void addEventTokens(List<String> tokens, boolean add) {
        boolean custom = false;
        for (String token : tokens) {
            if (custom) {
                if (add) {
                    customSubclasses.add(token);
                } else {
                    customSubclasses.remove(token);
                }
                continue;
            }
            String name = token.toUpperCase(Locale.ROOT);
            if (add) {
                events.add(name);
            } else {
                events.remove(name);
                if (EventNames.ALL.equals(name)) {
                    events.clear();
                }
            }
            custom = EventNames.CUSTOM.equals(name);
        }
    }

    public synchronized boolean isSubscribed(String eventName) {
        return events.contains(EventNames.ALL) || events.contains(eventName.toUpperCase(Locale.ROOT));
    }

    public synchronized boolean myEventsEnabled() {
        return myEvents;
    }

    public synchronized void clear() {
        events.clear();
        customSubclasses.clear();
        filters.clear();
        logLevel = null;
        myEvents = false;
    }

    /** Commands that rebuild this state on a fresh socket, in a safe order. */
    public synchronized List<EslCommand> replayCommands(EventFormat defaultFormat) {
        List<EslCommand> commands = new ArrayList<>();
        List<String> tokens = new ArrayList<>();
        events.stream().filter(name -> !EventNames.CUSTOM.equals(name)).forEach(tokens::add);
        if (events.contains(EventNames.CUSTOM) || !customSubclasses.isEmpty()) {
            tokens.add(EventNames.CUSTOM);
            tokens.addAll(customSubclasses);
        }
        if (!tokens.isEmpty()) {
            commands.add(new EslCommand.Event(format != null ? format : defaultFormat, tokens));
        }
        filters.forEach(filter -> commands.add(new EslCommand.Filter(filter.getKey(), filter.getValue())));
        if (logLevel != null) {
            commands.add(new EslCommand.Log(logLevel));
        }
        return commands;
    }
}
