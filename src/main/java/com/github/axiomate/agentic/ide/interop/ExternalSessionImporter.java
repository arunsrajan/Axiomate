package com.github.axiomate.agentic.ide.interop;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.axiomate.agentic.ide.agent.AgentMessage;
import com.github.axiomate.agentic.ide.agent.AgentRole;
import com.github.axiomate.agentic.ide.agent.session.AgentSession;
import com.github.axiomate.agentic.ide.config.ProjectStateManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Imports conversation transcripts recorded by other coding agents into Axiomate agent sessions:
 * <ul>
 *   <li>Claude Code: {@code ~/.claude/projects/<encoded-project>/<session-id>.jsonl}</li>
 *   <li>OpenAI Codex: {@code ~/.codex/sessions/YYYY/MM/DD/rollout-*.jsonl} (filtered by the session's cwd)</li>
 * </ul>
 * User prompts, assistant replies, reasoning, tool calls and tool results are mapped onto the
 * corresponding {@link AgentRole}s. Imported sessions get deterministic ids so re-importing replaces them.
 */
public class ExternalSessionImporter {

    private static final Logger log = LoggerFactory.getLogger(ExternalSessionImporter.class);
    private static final int MAX_CONTENT_CHARS = 20_000;
    private static final int MAX_MESSAGES = 4_000;
    private static final int SCAN_LINE_LIMIT = 600;
    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    public record ExternalSessionRef(CodingAgent agent, Path file, String externalId, String title,
                                     String startedAt, String cwd, long sizeBytes) {
        @Override
        public String toString() {
            return agent.getDisplayName() + " · " + title;
        }
    }

    private final ObjectMapper mapper = new ObjectMapper();
    private final Path homeDir;

    public ExternalSessionImporter() {
        this(Paths.get(System.getProperty("user.home", ".")));
    }

    public ExternalSessionImporter(Path homeDir) {
        this.homeDir = homeDir;
    }

    public static boolean supports(CodingAgent agent) {
        return agent == CodingAgent.CLAUDE_CODE || agent == CodingAgent.CODEX;
    }

    public List<ExternalSessionRef> findAll(Path projectDir) {
        List<ExternalSessionRef> all = new ArrayList<>();
        all.addAll(findSessions(CodingAgent.CLAUDE_CODE, projectDir));
        all.addAll(findSessions(CodingAgent.CODEX, projectDir));
        all.sort(Comparator.comparing((ExternalSessionRef r) -> r.startedAt() == null ? "" : r.startedAt()).reversed());
        return all;
    }

    public List<ExternalSessionRef> findSessions(CodingAgent agent, Path projectDir) {
        try {
            return switch (agent) {
                case CLAUDE_CODE -> findClaudeSessions(projectDir);
                case CODEX -> findCodexSessions(projectDir);
                default -> List.of();
            };
        } catch (IOException e) {
            log.warn("Could not list {} sessions: {}", agent.getDisplayName(), e.getMessage());
            return List.of();
        }
    }

    public AgentSession load(ExternalSessionRef ref) throws IOException {
        return switch (ref.agent()) {
            case CLAUDE_CODE -> loadClaude(ref);
            case CODEX -> loadCodex(ref);
            default -> throw new IOException("Session import is not supported for " + ref.agent().getDisplayName());
        };
    }

    // ------------------------------------------------------------------
    // Claude Code
    // ------------------------------------------------------------------

    private List<ExternalSessionRef> findClaudeSessions(Path projectDir) throws IOException {
        if (projectDir == null) return List.of();
        Path dir = homeDir.resolve(".claude").resolve("projects").resolve(CodingAgentCatalog.encodeClaudeProjectDir(projectDir));
        if (!Files.isDirectory(dir)) return List.of();
        List<ExternalSessionRef> refs = new ArrayList<>();
        try (Stream<Path> s = Files.list(dir)) {
            for (Path f : s.filter(p -> p.getFileName().toString().endsWith(".jsonl")).sorted().toList()) {
                ExternalSessionRef ref = scanClaude(f);
                if (ref != null) refs.add(ref);
            }
        }
        return refs;
    }

    private ExternalSessionRef scanClaude(Path file) throws IOException {
        String sessionId = file.getFileName().toString().replace(".jsonl", "");
        String title = null;
        String summary = null;
        String started = null;
        String cwd = null;
        int lines = 0;
        try (BufferedReader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            String line;
            while ((line = r.readLine()) != null && lines++ < SCAN_LINE_LIMIT) {
                JsonNode n = readLine(line);
                if (n == null) continue;
                String type = n.path("type").asText();
                if ("summary".equals(type) && summary == null) summary = n.path("summary").asText(null);
                if (started == null && n.hasNonNull("timestamp")) started = formatTs(n.get("timestamp").asText());
                if (cwd == null && n.hasNonNull("cwd")) cwd = n.get("cwd").asText();
                if (title == null && "user".equals(type) && !n.path("isSidechain").asBoolean(false)) {
                    title = firstUserText(n.path("message").path("content"));
                }
                if (title != null && summary != null) break;
            }
        }
        if (title == null && summary == null) return null; // no conversation content
        return new ExternalSessionRef(CodingAgent.CLAUDE_CODE, file, sessionId,
                shorten(summary != null ? summary : title, 70), started, cwd, sizeOf(file));
    }

    private AgentSession loadClaude(ExternalSessionRef ref) throws IOException {
        List<AgentMessage> messages = new ArrayList<>();
        Map<String, String> toolNames = new HashMap<>();
        String model = null;
        String lastAssistantId = null;

        try (BufferedReader r = Files.newBufferedReader(ref.file(), StandardCharsets.UTF_8)) {
            String line;
            while ((line = r.readLine()) != null && messages.size() < MAX_MESSAGES) {
                JsonNode n = readLine(line);
                if (n == null) continue;
                String type = n.path("type").asText();
                if (!"user".equals(type) && !"assistant".equals(type)) continue;
                if (n.path("isSidechain").asBoolean(false) || n.path("isMeta").asBoolean(false)) continue;

                JsonNode msg = n.path("message");
                String time = timeOf(n);
                boolean assistant = "assistant".equals(type);
                if (assistant && msg.hasNonNull("model")) model = msg.get("model").asText();
                String msgId = msg.path("id").asText(null);
                JsonNode content = msg.path("content");

                if (content.isTextual()) {
                    String text = content.asText();
                    if (!assistant && isClaudeNoise(text)) continue;
                    messages.add(new AgentMessage(assistant ? AgentRole.ASSISTANT : AgentRole.USER, clip(text), null, time));
                    continue;
                }
                for (JsonNode block : content) {
                    String bt = block.path("type").asText();
                    switch (bt) {
                        case "text" -> {
                            String text = block.path("text").asText("");
                            if (text.isBlank() || (!assistant && isClaudeNoise(text))) continue;
                            AgentRole role = assistant ? AgentRole.ASSISTANT : AgentRole.USER;
                            AgentMessage last = messages.isEmpty() ? null : messages.get(messages.size() - 1);
                            if (assistant && last != null && last.getRole() == AgentRole.ASSISTANT
                                    && msgId != null && msgId.equals(lastAssistantId)) {
                                last.setContent(clip(last.getContent() + "\n\n" + text));
                            } else {
                                messages.add(new AgentMessage(role, clip(text), null, time));
                            }
                            if (assistant) lastAssistantId = msgId;
                        }
                        case "thinking" -> {
                            String thought = block.path("thinking").asText("");
                            if (!thought.isBlank()) messages.add(new AgentMessage(AgentRole.THINKING, clip(thought), null, time));
                        }
                        case "tool_use" -> {
                            String name = block.path("name").asText("tool");
                            toolNames.put(block.path("id").asText(), name);
                            messages.add(new AgentMessage(AgentRole.TOOL_CALL, clip(block.path("input").toString()), name, time));
                        }
                        case "tool_result" -> {
                            String name = toolNames.getOrDefault(block.path("tool_use_id").asText(), "tool");
                            String out = textOf(block.path("content"));
                            if (block.path("is_error").asBoolean(false)) out = "[error] " + out;
                            messages.add(new AgentMessage(AgentRole.TOOL, clip(out), name, time));
                        }
                        default -> {
                        }
                    }
                }
            }
        }
        return toSession(ref, "ANTHROPIC", model != null ? model : "claude-sonnet", 200_000, messages);
    }

    private static boolean isClaudeNoise(String text) {
        String t = text.stripLeading();
        return t.startsWith("<command-") || t.startsWith("<local-command") || t.startsWith("<system-reminder>")
                || t.startsWith("Caveat: The messages below were generated");
    }

    private String firstUserText(JsonNode content) {
        if (content.isTextual()) {
            String t = content.asText();
            return isClaudeNoise(t) || t.isBlank() ? null : t;
        }
        for (JsonNode b : content) {
            if ("text".equals(b.path("type").asText())) {
                String t = b.path("text").asText("");
                if (!t.isBlank() && !isClaudeNoise(t)) return t;
            }
        }
        return null;
    }

    // ------------------------------------------------------------------
    // Codex
    // ------------------------------------------------------------------

    private List<ExternalSessionRef> findCodexSessions(Path projectDir) throws IOException {
        Path root = homeDir.resolve(".codex").resolve("sessions");
        if (!Files.isDirectory(root)) return List.of();
        String wanted = projectDir != null ? ProjectStateManager.normalizePath(projectDir.toFile()) : null;
        List<Path> files;
        try (Stream<Path> s = Files.walk(root, 5)) {
            files = s.filter(p -> {
                String n = p.getFileName().toString();
                return n.startsWith("rollout-") && n.endsWith(".jsonl");
            }).sorted(Comparator.reverseOrder()).collect(Collectors.toList());
        }
        List<ExternalSessionRef> refs = new ArrayList<>();
        for (Path f : files) {
            ExternalSessionRef ref = scanCodex(f, wanted);
            if (ref == null) continue;
            if (wanted != null && (ref.cwd() == null || !sameProject(ref.cwd(), wanted))) {
                continue;
            }
            refs.add(ref);
        }
        return refs;
    }

    private static boolean sameProject(String cwd, String wantedNormalized) {
        return ProjectStateManager.normalizePath(Paths.get(cwd).toFile()).equals(wantedNormalized);
    }

    /**
     * Reads a rollout's metadata and first prompt; stops early when the session belongs to another project.
     */
    private ExternalSessionRef scanCodex(Path file, String wantedProject) throws IOException {
        String id = null;
        String cwd = null;
        String started = null;
        String title = null;
        int lines = 0;
        try (BufferedReader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            String line;
            while ((line = r.readLine()) != null && lines++ < SCAN_LINE_LIMIT) {
                JsonNode n = readLine(line);
                if (n == null) continue;
                if (started == null && n.hasNonNull("timestamp")) started = formatTs(n.get("timestamp").asText());
                JsonNode item = n.has("payload") ? n.get("payload") : n;
                String type = n.path("type").asText();
                if ("session_meta".equals(type) || (lines == 1 && !n.has("payload") && n.has("id") && !n.has("role"))) {
                    id = item.path("id").asText(id);
                    cwd = item.path("cwd").asText(cwd);
                    if (item.hasNonNull("timestamp")) started = formatTs(item.get("timestamp").asText());
                    if (wantedProject != null && cwd != null && !sameProject(cwd, wantedProject)) {
                        return null; // another project's session: skip the rest of the file
                    }
                    continue;
                }
                if ("turn_context".equals(type) && cwd == null) cwd = item.path("cwd").asText(null);
                if (title == null && "message".equals(item.path("type").asText()) && "user".equals(item.path("role").asText())) {
                    String text = codexText(item.path("content"));
                    if (!isCodexNoise(text)) title = text;
                }
                if (title != null && cwd != null) break;
            }
        }
        if (title == null) return null;
        if (id == null) id = file.getFileName().toString().replace(".jsonl", "");
        return new ExternalSessionRef(CodingAgent.CODEX, file, id, shorten(title, 70), started, cwd, sizeOf(file));
    }

    private AgentSession loadCodex(ExternalSessionRef ref) throws IOException {
        List<AgentMessage> messages = new ArrayList<>();
        Map<String, String> toolNames = new HashMap<>();
        String model = null;
        try (BufferedReader r = Files.newBufferedReader(ref.file(), StandardCharsets.UTF_8)) {
            String line;
            while ((line = r.readLine()) != null && messages.size() < MAX_MESSAGES) {
                JsonNode n = readLine(line);
                if (n == null) continue;
                String type = n.path("type").asText();
                JsonNode item;
                if (n.has("payload")) {
                    if ("turn_context".equals(type)) {
                        model = n.get("payload").path("model").asText(model);
                        continue;
                    }
                    if (!"response_item".equals(type)) continue;
                    item = n.get("payload");
                } else {
                    item = n; // legacy rollout format: items written directly
                }
                String time = timeOf(n);
                switch (item.path("type").asText()) {
                    case "message" -> {
                        String role = item.path("role").asText();
                        String text = codexText(item.path("content"));
                        if (text.isBlank()) continue;
                        if ("user".equals(role) && !isCodexNoise(text)) {
                            messages.add(new AgentMessage(AgentRole.USER, clip(text), null, time));
                        } else if ("assistant".equals(role)) {
                            messages.add(new AgentMessage(AgentRole.ASSISTANT, clip(text), null, time));
                        }
                    }
                    case "reasoning" -> {
                        String text = codexText(item.path("summary"));
                        if (text.isBlank()) text = codexText(item.path("content"));
                        if (!text.isBlank()) messages.add(new AgentMessage(AgentRole.THINKING, clip(text), null, time));
                    }
                    case "function_call" -> {
                        String name = item.path("name").asText("tool");
                        toolNames.put(item.path("call_id").asText(), name);
                        messages.add(new AgentMessage(AgentRole.TOOL_CALL, clip(item.path("arguments").asText("")), name, time));
                    }
                    case "custom_tool_call" -> {
                        String name = item.path("name").asText("tool");
                        toolNames.put(item.path("call_id").asText(), name);
                        messages.add(new AgentMessage(AgentRole.TOOL_CALL, clip(item.path("input").asText("")), name, time));
                    }
                    case "local_shell_call" -> {
                        List<String> cmd = new ArrayList<>();
                        item.path("action").path("command").forEach(c -> cmd.add(c.asText()));
                        toolNames.put(item.path("call_id").asText(), "shell");
                        messages.add(new AgentMessage(AgentRole.TOOL_CALL, clip(String.join(" ", cmd)), "shell", time));
                    }
                    case "web_search_call" -> messages.add(new AgentMessage(AgentRole.TOOL_CALL,
                            clip(item.path("action").path("query").asText("")), "web_search", time));
                    case "function_call_output", "custom_tool_call_output" -> {
                        String name = toolNames.getOrDefault(item.path("call_id").asText(), "tool");
                        messages.add(new AgentMessage(AgentRole.TOOL, clip(codexOutput(item.path("output"))), name, time));
                    }
                    default -> {
                    }
                }
            }
        }
        return toSession(ref, "OPENAI", model != null ? model : "gpt-5-codex", 272_000, messages);
    }

    private static boolean isCodexNoise(String text) {
        String t = text.stripLeading();
        return t.startsWith("<environment_context>") || t.startsWith("<user_instructions>")
                || t.startsWith("# AGENTS.md instructions") || t.startsWith("<permissions");
    }

    private static String codexText(JsonNode content) {
        if (content.isTextual()) return content.asText();
        StringBuilder sb = new StringBuilder();
        for (JsonNode b : content) {
            String t = b.path("text").asText("");
            if (!t.isBlank()) {
                if (sb.length() > 0) sb.append("\n\n");
                sb.append(t);
            }
        }
        return sb.toString();
    }

    private String codexOutput(JsonNode output) {
        if (output.isTextual()) {
            String s = output.asText();
            if (s.startsWith("{")) {
                JsonNode parsed = readLine(s);
                if (parsed != null && parsed.hasNonNull("output")) return parsed.get("output").asText();
            }
            return s;
        }
        if (output.hasNonNull("content")) return textOf(output.get("content"));
        return output.toString();
    }

    // ------------------------------------------------------------------
    // Shared helpers
    // ------------------------------------------------------------------

    private AgentSession toSession(ExternalSessionRef ref, String provider, String model, int maxCtx,
                                   List<AgentMessage> messages) {
        AgentSession session = new AgentSession(ref.agent().getDisplayName() + ": " + shorten(ref.title(), 48),
                provider, model, maxCtx);
        session.setId(ref.agent().getId() + "-" + ref.externalId());
        session.setOrigin("Imported from " + ref.agent().getDisplayName() + " (" + ref.file().getFileName() + ")");
        if (ref.startedAt() != null) session.setCreatedAt(ref.startedAt());
        for (AgentMessage m : messages) {
            session.addMessage(m);
        }
        // Sort by when the conversation happened, not when it was imported
        if (ref.startedAt() != null) session.setUpdatedAt(ref.startedAt());
        log.info("Imported {} session '{}' with {} messages", ref.agent().getDisplayName(), ref.title(), messages.size());
        return session;
    }

    private JsonNode readLine(String line) {
        if (line == null || line.isBlank()) return null;
        try {
            return mapper.readTree(line);
        } catch (Exception e) {
            return null;
        }
    }

    private static String textOf(JsonNode content) {
        if (content == null || content.isMissingNode() || content.isNull()) return "";
        if (content.isTextual()) return content.asText();
        if (content.isArray()) {
            StringBuilder sb = new StringBuilder();
            for (JsonNode b : content) {
                String t = b.isTextual() ? b.asText() : b.path("text").asText("");
                if (!t.isEmpty()) {
                    if (sb.length() > 0) sb.append('\n');
                    sb.append(t);
                }
            }
            return sb.toString();
        }
        return content.toString();
    }

    private static String timeOf(JsonNode n) {
        String ts = n.path("timestamp").asText(null);
        if (ts == null) return null;
        try {
            return OffsetDateTime.parse(ts).atZoneSameInstant(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("HH:mm:ss"));
        } catch (Exception e) {
            return null;
        }
    }

    private static String formatTs(String iso) {
        try {
            return OffsetDateTime.parse(iso).atZoneSameInstant(ZoneId.systemDefault()).format(TS);
        } catch (Exception e) {
            return iso;
        }
    }

    private static String clip(String s) {
        if (s == null) return "";
        return s.length() <= MAX_CONTENT_CHARS ? s : s.substring(0, MAX_CONTENT_CHARS) + "\n… (truncated during import)";
    }

    private static String shorten(String s, int max) {
        if (s == null) return "Untitled session";
        String oneLine = s.replaceAll("\\s+", " ").trim();
        return oneLine.length() <= max ? oneLine : oneLine.substring(0, max - 1) + "…";
    }

    private static long sizeOf(Path p) {
        try {
            return Files.size(p);
        } catch (IOException e) {
            return 0;
        }
    }
}
