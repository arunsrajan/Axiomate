package com.github.axiomate.agentic.ide.plugins;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * Registry of slash commands available in the agent chat. Commands come from plugins, from other coding
 * agents' command folders (Claude Code commands, Codex prompts, Antigravity workflows...), or are built-in
 * IDE actions.
 */
public class SlashCommandRegistry {

    private static final Logger log = LoggerFactory.getLogger(SlashCommandRegistry.class);
    private static SlashCommandRegistry instance;

    /**
     * @param template prompt template ({@code $ARGUMENTS}, {@code $1..$9}); ignored when {@code action} is set
     * @param source   who registered the command, e.g. "builtin", "plugin:java-rules", "claude-code"
     * @param action   IDE action to run instead of sending a prompt, or null
     */
    public record SlashCommand(String name, String description, String template, String source,
                               Consumer<String> action) {

        public SlashCommand(String name, String description, String template, String source) {
            this(name, description, template, source, null);
        }

        private static final java.util.regex.Pattern PLACEHOLDER = java.util.regex.Pattern.compile("\\$(ARGUMENTS|[1-9])(?!\\d)");

        public boolean isAction() {
            return action != null;
        }

        public String expand(String arguments) {
            String args = arguments == null ? "" : arguments.trim();
            String t = template == null ? "" : template;
            boolean hasPlaceholder = PLACEHOLDER.matcher(t).find();
            String[] words = args.isEmpty() ? new String[0] : args.split("\\s+");
            // One pass over the template only: "$5" typed by the user must stay "$5"
            java.util.regex.Matcher m = PLACEHOLDER.matcher(t);
            StringBuilder sb = new StringBuilder();
            while (m.find()) {
                String key = m.group(1);
                String value = "ARGUMENTS".equals(key) ? args
                        : Integer.parseInt(key) <= words.length ? words[Integer.parseInt(key) - 1] : "";
                m.appendReplacement(sb, java.util.regex.Matcher.quoteReplacement(value));
            }
            m.appendTail(sb);
            String out = sb.toString();
            if (!hasPlaceholder && !args.isEmpty()) {
                out = out.stripTrailing() + "\n\n" + args;
            }
            return out.strip();
        }
    }

    public enum Outcome {NOT_A_COMMAND, EXECUTED, EXPANDED, UNKNOWN}

    public record Dispatch(Outcome outcome, String prompt, SlashCommand command) {
    }

    private final Map<String, SlashCommand> commands = new LinkedHashMap<>();
    private final List<Runnable> listeners = new CopyOnWriteArrayList<>();

    public static synchronized SlashCommandRegistry getInstance() {
        if (instance == null) {
            instance = new SlashCommandRegistry();
        }
        return instance;
    }

    public static String normalizeName(String name) {
        String n = name == null ? "" : name.trim();
        if (n.startsWith("/")) n = n.substring(1);
        return n.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9:_.-]+", "-");
    }

    public synchronized void register(SlashCommand command) {
        if (command == null || command.name() == null || command.name().isBlank()) return;
        String key = normalizeName(command.name());
        SlashCommand existing = commands.get(key);
        // Built-in IDE actions cannot be shadowed by plugin or agent commands
        if (existing != null && "builtin".equals(existing.source()) && !"builtin".equals(command.source())) {
            log.debug("Ignoring command /{} from {}: name reserved by a built-in", key, command.source());
            return;
        }
        commands.put(key, new SlashCommand(key, command.description(), command.template(), command.source(), command.action()));
        notifyListeners();
    }

    public synchronized void registerAll(List<SlashCommand> list) {
        for (SlashCommand c : list) register(c);
    }

    public synchronized int unregisterSource(String source) {
        int before = commands.size();
        commands.values().removeIf(c -> c.source() != null && c.source().equals(source));
        int removed = before - commands.size();
        if (removed > 0) notifyListeners();
        return removed;
    }

    public synchronized SlashCommand find(String name) {
        return commands.get(normalizeName(name));
    }

    public synchronized List<SlashCommand> all() {
        List<SlashCommand> list = new ArrayList<>(commands.values());
        list.sort(Comparator.comparing(SlashCommand::name));
        return list;
    }

    /**
     * Commands whose name starts with the given prefix (without the leading slash).
     */
    public synchronized List<SlashCommand> complete(String prefix) {
        String p = normalizeName(prefix);
        List<SlashCommand> list = new ArrayList<>();
        for (SlashCommand c : all()) {
            if (c.name().startsWith(p)) list.add(c);
        }
        return list;
    }

    /**
     * Interprets chat input: runs built-in actions, expands prompt commands, or reports unknown commands.
     */
    public Dispatch dispatch(String input) {
        if (input == null) return new Dispatch(Outcome.NOT_A_COMMAND, null, null);
        String trimmed = input.strip();
        if (!trimmed.startsWith("/") || trimmed.length() < 2 || Character.isWhitespace(trimmed.charAt(1))
                || trimmed.startsWith("//")) {
            return new Dispatch(Outcome.NOT_A_COMMAND, input, null);
        }
        int space = indexOfWhitespace(trimmed);
        String name = space < 0 ? trimmed.substring(1) : trimmed.substring(1, space);
        if (name.contains("/")) {
            return new Dispatch(Outcome.NOT_A_COMMAND, input, null); // looks like a path, e.g. /usr/bin
        }
        String args = space < 0 ? "" : trimmed.substring(space + 1);
        SlashCommand cmd = find(name);
        if (cmd == null) return new Dispatch(Outcome.UNKNOWN, input, null);
        if (cmd.isAction()) {
            cmd.action().accept(args);
            return new Dispatch(Outcome.EXECUTED, null, cmd);
        }
        return new Dispatch(Outcome.EXPANDED, cmd.expand(args), cmd);
    }

    private static int indexOfWhitespace(String s) {
        for (int i = 0; i < s.length(); i++) {
            if (Character.isWhitespace(s.charAt(i))) return i;
        }
        return -1;
    }

    public void addChangeListener(Runnable r) {
        listeners.add(r);
    }

    private void notifyListeners() {
        for (Runnable r : listeners) {
            try {
                r.run();
            } catch (Exception e) {
                log.warn("Slash command listener failed", e);
            }
        }
    }
}
