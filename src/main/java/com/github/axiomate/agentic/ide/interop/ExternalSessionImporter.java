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
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Imports conversation transcripts recorded by other coding agents into Axiomate agent sessions:
 * <ul>
 *   <li>Claude Code: {@code ~/.claude/projects/<encoded-project>/<session-id>.jsonl} (respects {@code CLAUDE_CONFIG_DIR})</li>
 *   <li>OpenAI Codex: {@code ~/.codex/sessions/YYYY/MM/DD/rollout-*.jsonl} (respects {@code CODEX_HOME}, filtered by cwd)</li>
 *   <li>Google Antigravity: {@code ~/.gemini/antigravity-ide/brain/<conv-id>/.system_generated/logs/transcript.jsonl}</li>
 *   <li>Gemini CLI: {@code ~/.gemini/chats/*.json} or {@code ~/.gemini/sessions/*.jsonl} (respects {@code GEMINI_CLI_HOME})</li>
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
    private final AgentEnvironment env;
    private final Path homeDir;

    public ExternalSessionImporter() {
        this(AgentEnvironment.system());
    }

    public ExternalSessionImporter(Path homeDir) {
        this(AgentEnvironment.isolated(homeDir));
    }

    public ExternalSessionImporter(AgentEnvironment env) {
        this.env = env != null ? env : AgentEnvironment.system();
        this.homeDir = this.env.homeDir();
    }

    public AgentEnvironment env() {
        return env;
    }

    public static boolean supports(CodingAgent agent) {
        return agent == CodingAgent.CLAUDE_CODE
                || agent == CodingAgent.CODEX
                || agent == CodingAgent.ANTIGRAVITY
                || agent == CodingAgent.GEMINI_CLI;
    }

    public List<ExternalSessionRef> findAll(Path projectDir) {
        List<ExternalSessionRef> all = new ArrayList<>();
        all.addAll(findSessions(CodingAgent.CLAUDE_CODE, projectDir));
        all.addAll(findSessions(CodingAgent.CODEX, projectDir));
        all.addAll(findSessions(CodingAgent.ANTIGRAVITY, projectDir));
        all.addAll(findSessions(CodingAgent.GEMINI_CLI, projectDir));
        all.sort(Comparator.comparing((ExternalSessionRef r) -> r.startedAt() == null ? "" : r.startedAt()).reversed());
        return all;
    }

    public List<ExternalSessionRef> findSessions(CodingAgent agent, Path projectDir) {
        try {
            return switch (agent) {
                case CLAUDE_CODE -> findClaudeSessions(projectDir);
                case CODEX -> findCodexSessions(projectDir);
                case ANTIGRAVITY -> findAntigravitySessions(projectDir);
                case GEMINI_CLI -> findGeminiCliSessions(projectDir);
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
            case ANTIGRAVITY -> loadAntigravity(ref);
            case GEMINI_CLI -> loadGeminiCli(ref);
            default -> throw new IOException("Session import is not supported for " + ref.agent().getDisplayName());
        };
    }

    // ------------------------------------------------------------------
    // Claude Code
    // ------------------------------------------------------------------

    private List<ExternalSessionRef> findClaudeSessions(Path projectDir) throws IOException {
        Path projectsRoot = env.claudeHome().resolve("projects");
        if (!Files.isDirectory(projectsRoot)) return List.of();
        if (projectDir != null) {
            Path dir = projectsRoot.resolve(CodingAgentCatalog.encodeClaudeProjectDir(projectDir));
            return scanClaudeDir(dir);
        }
        List<ExternalSessionRef> refs = new ArrayList<>();
        try (Stream<Path> projDirs = Files.list(projectsRoot)) {
            for (Path dir : projDirs.filter(Files::isDirectory).toList()) {
                refs.addAll(scanClaudeDir(dir));
            }
        }
        return refs;
    }

    private List<ExternalSessionRef> scanClaudeDir(Path dir) throws IOException {
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
        Path root = env.codexHome().resolve("sessions");
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
            if (wanted != null && (ref.cwd() == null || !matchesProject(ref.cwd(), wanted))) {
                continue;
            }
            refs.add(ref);
        }
        return refs;
    }

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
                    if (wantedProject != null && cwd != null && !matchesProject(cwd, wantedProject)) {
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
    // Google Antigravity
    // ------------------------------------------------------------------

    private List<ExternalSessionRef> findAntigravitySessions(Path projectDir) throws IOException {
        String wanted = projectDir != null ? ProjectStateManager.normalizePath(projectDir.toFile()) : null;
        List<Path> roots = new ArrayList<>();
        roots.add(env.geminiHome().resolve("antigravity-ide").resolve("brain"));
        roots.add(env.geminiHome().resolve("antigravity").resolve("brain"));
        roots.add(env.geminiHome().resolve("brain"));
        roots.add(homeDir.resolve(".gemini").resolve("antigravity-ide").resolve("brain"));
        roots.add(homeDir.resolve(".gemini").resolve("antigravity").resolve("brain"));
        if (projectDir != null) {
            roots.add(projectDir.resolve(".gemini").resolve("brain"));
        }

        Set<Path> existingRoots = roots.stream()
                .filter(Files::isDirectory)
                .map(p -> p.toAbsolutePath().normalize())
                .collect(Collectors.toCollection(LinkedHashSet::new));

        Set<String> seenIds = new HashSet<>();
        List<ExternalSessionRef> refs = new ArrayList<>();

        for (Path root : existingRoots) {
            try (Stream<Path> convDirs = Files.list(root)) {
                for (Path convDir : convDirs.filter(Files::isDirectory).toList()) {
                    String convId = convDir.getFileName().toString();
                    if (seenIds.contains(convId)) continue;

                    Path transcript = resolveAntigravityTranscript(convDir);
                    if (transcript == null || !Files.isRegularFile(transcript)) continue;

                    ExternalSessionRef ref = scanAntigravity(transcript, convId, wanted);
                    if (ref != null && seenIds.add(convId)) {
                        refs.add(ref);
                    }
                }
            }
        }
        return refs;
    }

    private static Path resolveAntigravityTranscript(Path convDir) {
        Path p = convDir.resolve(".system_generated").resolve("logs").resolve("transcript.jsonl");
        if (Files.isRegularFile(p)) return p;
        p = convDir.resolve(".system_generated").resolve("logs").resolve("transcript_full.jsonl");
        if (Files.isRegularFile(p)) return p;
        p = convDir.resolve("logs").resolve("transcript.jsonl");
        if (Files.isRegularFile(p)) return p;
        p = convDir.resolve("transcript.jsonl");
        if (Files.isRegularFile(p)) return p;
        return null;
    }

    private ExternalSessionRef scanAntigravity(Path file, String convId, String wantedProject) throws IOException {
        String title = null;
        String started = null;
        String cwd = null;
        int lines = 0;

        try (BufferedReader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            String line;
            while ((line = r.readLine()) != null && lines++ < SCAN_LINE_LIMIT) {
                JsonNode n = readLine(line);
                if (n == null) continue;

                if (started == null && n.hasNonNull("created_at")) {
                    started = formatTs(n.get("created_at").asText());
                }

                String type = n.path("type").asText();
                String source = n.path("source").asText();

                if (cwd == null) {
                    cwd = extractAntigravityCwd(line, n);
                }

                if (title == null && ("USER_INPUT".equalsIgnoreCase(type) || "USER_EXPLICIT".equalsIgnoreCase(source))) {
                    String raw = n.path("content").asText("");
                    String extracted = extractAntigravityUserText(raw);
                    if (!extracted.isBlank()) {
                        title = extracted;
                    }
                }

                if (title != null && cwd != null) break;
            }
        }

        if (title == null) return null;
        if (wantedProject != null && cwd != null && !matchesProject(cwd, wantedProject)) {
            return null;
        }

        return new ExternalSessionRef(CodingAgent.ANTIGRAVITY, file, convId,
                shorten(title, 70), started, cwd, sizeOf(file));
    }

    private AgentSession loadAntigravity(ExternalSessionRef ref) throws IOException {
        List<AgentMessage> messages = new ArrayList<>();
        String model = "gemini-2.5-pro";

        try (BufferedReader r = Files.newBufferedReader(ref.file(), StandardCharsets.UTF_8)) {
            String line;
            while ((line = r.readLine()) != null && messages.size() < MAX_MESSAGES) {
                JsonNode n = readLine(line);
                if (n == null) continue;

                String type = n.path("type").asText();
                String source = n.path("source").asText();
                String time = timeOfIso(n.path("created_at").asText(null));

                if ("USER_INPUT".equalsIgnoreCase(type) || "USER_EXPLICIT".equalsIgnoreCase(source)) {
                    String raw = n.path("content").asText("");
                    Matcher mModel = Pattern.compile("Model Selection` from [^`]+ to ([^`\\n(]+)").matcher(raw);
                    if (mModel.find()) {
                        model = mModel.group(1).trim().toLowerCase().replace(" ", "-");
                    }
                    String userText = extractAntigravityUserText(raw);
                    if (!userText.isBlank()) {
                        messages.add(new AgentMessage(AgentRole.USER, clip(userText), null, time));
                    }
                } else if ("PLANNER_RESPONSE".equalsIgnoreCase(type)) {
                    if (n.hasNonNull("thinking")) {
                        String thought = n.get("thinking").asText("");
                        if (!thought.isBlank()) {
                            messages.add(new AgentMessage(AgentRole.THINKING, clip(thought), null, time));
                        }
                    }
                    if (n.hasNonNull("content")) {
                        String reply = n.get("content").asText("");
                        if (!reply.isBlank()) {
                            messages.add(new AgentMessage(AgentRole.ASSISTANT, clip(reply), null, time));
                        }
                    }
                    if (n.hasNonNull("tool_calls")) {
                        JsonNode toolCalls = n.get("tool_calls");
                        if (toolCalls.isArray()) {
                            for (JsonNode tc : toolCalls) {
                                String name = tc.path("name").asText("tool");
                                String args = tc.hasNonNull("args") ? tc.get("args").toString() : "";
                                messages.add(new AgentMessage(AgentRole.TOOL_CALL, clip(args), name, time));
                            }
                        }
                    }
                } else if ("MODEL".equalsIgnoreCase(source) && !"PLANNER_RESPONSE".equalsIgnoreCase(type)) {
                    String out = n.path("content").asText("");
                    if (!out.isBlank()) {
                        String toolName = type.toLowerCase().replace('_', ' ');
                        messages.add(new AgentMessage(AgentRole.TOOL, clip(out), toolName, time));
                    }
                }
            }
        }

        return toSession(ref, "GEMINI", model, 1_000_000, messages);
    }

    private static String extractAntigravityUserText(String content) {
        if (content == null || content.isBlank()) return "";
        int start = content.indexOf("<USER_REQUEST>");
        int end = content.indexOf("</USER_REQUEST>");
        if (start >= 0 && end > start) {
            return content.substring(start + "<USER_REQUEST>".length(), end).strip();
        }
        String s = content;
        int metaIdx = s.indexOf("<ADDITIONAL_METADATA>");
        if (metaIdx >= 0) {
            s = s.substring(0, metaIdx);
        }
        int histIdx = s.indexOf("# Conversation History");
        if (histIdx >= 0) {
            s = s.substring(0, histIdx);
        }
        return s.strip();
    }

    private static String extractAntigravityCwd(String line, JsonNode node) {
        if (node != null) {
            if (node.hasNonNull("cwd")) return node.get("cwd").asText();
            if (node.hasNonNull("Cwd")) return node.get("Cwd").asText();
            String content = node.path("content").asText(null);
            if (content != null && !content.isBlank()) {
                String found = extractPathFromText(content);
                if (found != null) return found;
            }
        }
        if (line != null) {
            String found = extractPathFromText(line);
            if (found != null) return found;
        }
        return null;
    }

    private static String extractPathFromText(String text) {
        Matcher m = Pattern.compile("([A-Za-z]:[\\\\/][^\\r\\n\"'<>->]+|/[^\\r\\n\"'<>->]+)\\s*->").matcher(text);
        if (m.find()) return m.group(1).trim();
        Matcher mDoc = Pattern.compile("-\\s*([A-Za-z]:[\\\\/][^\\r\\n\"'()]+|/[^\\r\\n\"'()]+)").matcher(text);
        if (mDoc.find()) return mDoc.group(1).trim();
        Matcher mCwd = Pattern.compile("\"Cwd\"\\s*:\\s*\"([^\"]+)\"").matcher(text);
        if (mCwd.find()) return mCwd.group(1).replace("\\\\", "\\");
        return null;
    }

    // ------------------------------------------------------------------
    // Google Gemini CLI
    // ------------------------------------------------------------------

    private List<ExternalSessionRef> findGeminiCliSessions(Path projectDir) throws IOException {
        String wanted = projectDir != null ? ProjectStateManager.normalizePath(projectDir.toFile()) : null;
        List<Path> searchDirs = new ArrayList<>();
        searchDirs.add(env.geminiHome().resolve("chats"));
        searchDirs.add(env.geminiHome().resolve("sessions"));
        searchDirs.add(homeDir.resolve(".gemini").resolve("chats"));
        searchDirs.add(homeDir.resolve(".gemini").resolve("sessions"));
        if (projectDir != null) {
            searchDirs.add(projectDir.resolve(".gemini").resolve("chats"));
            searchDirs.add(projectDir.resolve(".gemini").resolve("sessions"));
        }

        Path tmp = env.geminiHome().resolve("tmp");
        if (Files.isDirectory(tmp)) {
            try (Stream<Path> walk = Files.walk(tmp, 4)) {
                walk.filter(Files::isDirectory)
                        .filter(p -> p.getFileName().toString().equals("chats") || p.getFileName().toString().equals("sessions"))
                        .forEach(searchDirs::add);
            }
        }

        Set<Path> existing = searchDirs.stream()
                .filter(Files::isDirectory)
                .map(p -> p.toAbsolutePath().normalize())
                .collect(Collectors.toCollection(LinkedHashSet::new));

        Set<String> seenIds = new HashSet<>();
        List<ExternalSessionRef> refs = new ArrayList<>();

        for (Path dir : existing) {
            try (Stream<Path> files = Files.list(dir)) {
                for (Path f : files.filter(Files::isRegularFile).toList()) {
                    String name = f.getFileName().toString().toLowerCase();
                    if (!name.endsWith(".json") && !name.endsWith(".jsonl")) continue;
                    ExternalSessionRef ref = scanGeminiCli(f, wanted);
                    if (ref != null && seenIds.add(ref.externalId())) {
                        refs.add(ref);
                    }
                }
            }
        }
        return refs;
    }

    private ExternalSessionRef scanGeminiCli(Path file, String wantedProject) throws IOException {
        String id = file.getFileName().toString().replaceAll("\\.(json|jsonl)$", "");
        String title = null;
        String started = null;
        String cwd = null;

        if (file.getFileName().toString().endsWith(".json")) {
            try {
                String text = Files.readString(file, StandardCharsets.UTF_8);
                JsonNode root = mapper.readTree(text);
                if (root == null) return null;
                if (root.isObject()) {
                    id = root.path("sessionId").asText(root.path("id").asText(id));
                    cwd = root.path("cwd").asText(root.path("project").asText(root.path("projectPath").asText(null)));
                    if (root.hasNonNull("created_at")) started = formatTs(root.get("created_at").asText());
                    else if (root.hasNonNull("timestamp")) started = formatTs(root.get("timestamp").asText());

                    JsonNode msgs = root.path("messages");
                    if (msgs.isArray()) {
                        for (JsonNode m : msgs) {
                            String role = m.path("role").asText();
                            if ("user".equalsIgnoreCase(role)) {
                                title = textOf(m.path("content").isMissingNode() ? m.path("parts") : m.path("content"));
                                if (started == null && m.hasNonNull("timestamp")) {
                                    started = formatTs(m.get("timestamp").asText());
                                }
                                if (!title.isBlank()) break;
                            }
                        }
                    }
                } else if (root.isArray()) {
                    for (JsonNode m : root) {
                        String role = m.path("role").asText();
                        if ("user".equalsIgnoreCase(role)) {
                            title = textOf(m.path("content").isMissingNode() ? m.path("parts") : m.path("content"));
                            if (started == null && m.hasNonNull("timestamp")) {
                                started = formatTs(m.get("timestamp").asText());
                            }
                            if (!title.isBlank()) break;
                        }
                    }
                }
            } catch (Exception e) {
                return null;
            }
        } else {
            int lines = 0;
            try (BufferedReader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                String line;
                while ((line = r.readLine()) != null && lines++ < SCAN_LINE_LIMIT) {
                    JsonNode n = readLine(line);
                    if (n == null) continue;
                    if (started == null && n.hasNonNull("timestamp")) started = formatTs(n.get("timestamp").asText());
                    if (cwd == null) {
                        cwd = n.path("cwd").asText(n.path("project").asText(null));
                    }
                    if (title == null && "user".equalsIgnoreCase(n.path("role").asText())) {
                        title = textOf(n.path("content").isMissingNode() ? n.path("parts") : n.path("content"));
                    }
                    if (title != null && cwd != null) break;
                }
            }
        }

        if (title == null || title.isBlank()) return null;
        if (wantedProject != null && cwd != null && !matchesProject(cwd, wantedProject)) {
            return null;
        }

        return new ExternalSessionRef(CodingAgent.GEMINI_CLI, file, id,
                shorten(title, 70), started, cwd, sizeOf(file));
    }

    private AgentSession loadGeminiCli(ExternalSessionRef ref) throws IOException {
        List<AgentMessage> messages = new ArrayList<>();
        String model = "gemini-2.5-pro";

        if (ref.file().getFileName().toString().endsWith(".json")) {
            String text = Files.readString(ref.file(), StandardCharsets.UTF_8);
            JsonNode root = mapper.readTree(text);
            if (root != null) {
                if (root.hasNonNull("model")) model = root.get("model").asText();
                JsonNode msgs = root.isObject() ? root.path("messages") : root;
                if (msgs.isArray()) {
                    for (JsonNode m : msgs) {
                        String role = m.path("role").asText().toLowerCase();
                        String time = timeOfIso(m.path("timestamp").asText(m.path("created_at").asText(null)));
                        String content = textOf(m.path("content").isMissingNode() ? m.path("parts") : m.path("content"));

                        if ("user".equals(role)) {
                            if (!content.isBlank()) messages.add(new AgentMessage(AgentRole.USER, clip(content), null, time));
                        } else if ("model".equals(role) || "assistant".equals(role)) {
                            if (m.hasNonNull("thought") || m.hasNonNull("thinking")) {
                                String thought = m.hasNonNull("thought") ? m.get("thought").asText() : m.get("thinking").asText();
                                if (!thought.isBlank()) messages.add(new AgentMessage(AgentRole.THINKING, clip(thought), null, time));
                            }
                            if (!content.isBlank()) messages.add(new AgentMessage(AgentRole.ASSISTANT, clip(content), null, time));
                            JsonNode calls = m.path("toolCalls").isMissingNode() ? m.path("function_calls") : m.path("toolCalls");
                            if (calls.isArray()) {
                                for (JsonNode tc : calls) {
                                    String name = tc.path("name").asText("tool");
                                    String args = tc.hasNonNull("args") ? tc.get("args").toString() : tc.path("arguments").asText("");
                                    messages.add(new AgentMessage(AgentRole.TOOL_CALL, clip(args), name, time));
                                }
                            }
                        } else if ("tool".equals(role) || "function".equals(role)) {
                            String name = m.path("name").asText("tool");
                            if (!content.isBlank()) messages.add(new AgentMessage(AgentRole.TOOL, clip(content), name, time));
                        }
                    }
                }
            }
        } else {
            try (BufferedReader r = Files.newBufferedReader(ref.file(), StandardCharsets.UTF_8)) {
                String line;
                while ((line = r.readLine()) != null && messages.size() < MAX_MESSAGES) {
                    JsonNode n = readLine(line);
                    if (n == null) continue;
                    if (n.hasNonNull("model")) model = n.get("model").asText();
                    String role = n.path("role").asText().toLowerCase();
                    String time = timeOfIso(n.path("timestamp").asText(n.path("created_at").asText(null)));
                    String content = textOf(n.path("content").isMissingNode() ? n.path("parts") : n.path("content"));

                    if ("user".equals(role)) {
                        if (!content.isBlank()) messages.add(new AgentMessage(AgentRole.USER, clip(content), null, time));
                    } else if ("model".equals(role) || "assistant".equals(role)) {
                        if (n.hasNonNull("thought") || n.hasNonNull("thinking")) {
                            String thought = n.hasNonNull("thought") ? n.get("thought").asText() : n.get("thinking").asText();
                            if (!thought.isBlank()) messages.add(new AgentMessage(AgentRole.THINKING, clip(thought), null, time));
                        }
                        if (!content.isBlank()) messages.add(new AgentMessage(AgentRole.ASSISTANT, clip(content), null, time));
                    } else if ("tool".equals(role) || "function".equals(role)) {
                        String name = n.path("name").asText("tool");
                        if (!content.isBlank()) messages.add(new AgentMessage(AgentRole.TOOL, clip(content), name, time));
                    }
                }
            }
        }

        return toSession(ref, "GEMINI", model, 1_000_000, messages);
    }

    // ------------------------------------------------------------------
    // Shared helpers
    // ------------------------------------------------------------------

    private static boolean matchesProject(String foundPath, String wantedNormalized) {
        if (foundPath == null || wantedNormalized == null) return false;
        try {
            String normFound = ProjectStateManager.normalizePath(Paths.get(foundPath).toFile());
            return normFound.equals(wantedNormalized)
                    || normFound.startsWith(wantedNormalized + "/")
                    || wantedNormalized.startsWith(normFound + "/");
        } catch (Exception e) {
            return false;
        }
    }

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
        return timeOfIso(ts);
    }

    private static String timeOfIso(String iso) {
        if (iso == null || iso.isBlank()) return null;
        try {
            return OffsetDateTime.parse(iso).atZoneSameInstant(ZoneId.systemDefault())
                    .format(DateTimeFormatter.ofPattern("HH:mm:ss"));
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
