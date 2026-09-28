package com.github.axiomate.agentic.ide.interop;

import com.github.axiomate.agentic.ide.agent.AgentMessage;
import com.github.axiomate.agentic.ide.agent.AgentRole;
import com.github.axiomate.agentic.ide.agent.session.AgentSession;
import com.github.axiomate.agentic.ide.interop.ExternalSessionImporter.ExternalSessionRef;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ExternalSessionImporterTest {

    @TempDir
    Path tmp;

    private static void write(Path file, String content) throws IOException {
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
    }

    @Test
    @DisplayName("Imports a Claude Code JSONL transcript with text, thinking, tool use and tool results")
    void claudeCode() throws IOException {
        Path home = Files.createDirectories(tmp.resolve("home"));
        Path project = Files.createDirectories(tmp.resolve("proj"));
        Path dir = home.resolve(".claude/projects/" + CodingAgentCatalog.encodeClaudeProjectDir(project));
        String cwd = project.toString().replace("\\", "\\\\");
        write(dir.resolve("abc-123.jsonl"), String.join("\n",
                "{\"type\":\"user\",\"sessionId\":\"abc-123\",\"cwd\":\"" + cwd + "\",\"timestamp\":\"2026-09-01T10:00:00Z\",\"message\":{\"role\":\"user\",\"content\":\"<command-name>/clear</command-name>\"}}",
                "{\"type\":\"user\",\"sessionId\":\"abc-123\",\"timestamp\":\"2026-09-01T10:00:01Z\",\"message\":{\"role\":\"user\",\"content\":\"Fix the failing Calculator test\"}}",
                "{\"type\":\"assistant\",\"timestamp\":\"2026-09-01T10:00:02Z\",\"message\":{\"id\":\"m1\",\"role\":\"assistant\",\"model\":\"claude-opus-4\",\"content\":[{\"type\":\"thinking\",\"thinking\":\"Look at the test first.\"}]}}",
                "{\"type\":\"assistant\",\"message\":{\"id\":\"m1\",\"role\":\"assistant\",\"content\":[{\"type\":\"tool_use\",\"id\":\"tu1\",\"name\":\"Read\",\"input\":{\"file_path\":\"Calculator.java\"}}]}}",
                "{\"type\":\"user\",\"message\":{\"role\":\"user\",\"content\":[{\"type\":\"tool_result\",\"tool_use_id\":\"tu1\",\"content\":[{\"type\":\"text\",\"text\":\"class Calculator {}\"}]}]}}",
                "{\"type\":\"assistant\",\"message\":{\"id\":\"m2\",\"role\":\"assistant\",\"content\":[{\"type\":\"text\",\"text\":\"Fixed the division\"}]}}",
                "{\"type\":\"assistant\",\"message\":{\"id\":\"m2\",\"role\":\"assistant\",\"content\":[{\"type\":\"text\",\"text\":\"by zero check.\"}]}}",
                "{\"type\":\"assistant\",\"isSidechain\":true,\"message\":{\"id\":\"s\",\"role\":\"assistant\",\"content\":[{\"type\":\"text\",\"text\":\"subagent noise\"}]}}",
                "not json at all"
        ));
        write(dir.resolve("empty.jsonl"), "{\"type\":\"summary\"}\n");

        ExternalSessionImporter importer = new ExternalSessionImporter(home);
        List<ExternalSessionRef> refs = importer.findSessions(CodingAgent.CLAUDE_CODE, project);
        assertEquals(1, refs.size(), "sessions without conversation content are skipped");
        assertEquals("Fix the failing Calculator test", refs.get(0).title());

        AgentSession session = importer.load(refs.get(0));
        assertEquals("claude-code-abc-123", session.getId());
        assertEquals("ANTHROPIC", session.getProviderId());
        assertEquals("claude-opus-4", session.getModelId());
        List<AgentRole> roles = session.getMessages().stream().map(AgentMessage::getRole).toList();
        assertEquals(List.of(AgentRole.USER, AgentRole.THINKING, AgentRole.TOOL_CALL, AgentRole.TOOL, AgentRole.ASSISTANT), roles);
        AgentMessage toolResult = session.getMessages().get(3);
        assertEquals("Read", toolResult.getToolName());
        assertEquals("class Calculator {}", toolResult.getContent());
        assertEquals("Fixed the division\n\nby zero check.", session.getMessages().get(4).getContent(),
                "streamed text blocks of the same message are merged");
        assertTrue(session.getOrigin().contains("Claude Code"));
    }

    @Test
    @DisplayName("Imports Codex rollout JSONL filtered by the session's working directory")
    void codex() throws IOException {
        Path home = Files.createDirectories(tmp.resolve("home"));
        Path project = Files.createDirectories(tmp.resolve("proj"));
        String cwd = project.toFile().getCanonicalPath().replace("\\", "\\\\");
        write(home.resolve(".codex/sessions/2026/09/01/rollout-2026-09-01T10-00-00-aaa.jsonl"), String.join("\n",
                "{\"timestamp\":\"2026-09-01T10:00:00Z\",\"type\":\"session_meta\",\"payload\":{\"id\":\"aaa\",\"cwd\":\"" + cwd + "\",\"timestamp\":\"2026-09-01T10:00:00Z\"}}",
                "{\"type\":\"turn_context\",\"payload\":{\"cwd\":\"" + cwd + "\",\"model\":\"gpt-5-codex\"}}",
                "{\"type\":\"response_item\",\"payload\":{\"type\":\"message\",\"role\":\"user\",\"content\":[{\"type\":\"input_text\",\"text\":\"<environment_context>cwd</environment_context>\"}]}}",
                "{\"type\":\"response_item\",\"payload\":{\"type\":\"message\",\"role\":\"user\",\"content\":[{\"type\":\"input_text\",\"text\":\"Add a README\"}]}}",
                "{\"type\":\"response_item\",\"payload\":{\"type\":\"reasoning\",\"summary\":[{\"type\":\"summary_text\",\"text\":\"Plan the README\"}]}}",
                "{\"type\":\"response_item\",\"payload\":{\"type\":\"function_call\",\"name\":\"shell\",\"arguments\":\"{\\\"command\\\":[\\\"ls\\\"]}\",\"call_id\":\"c1\"}}",
                "{\"type\":\"response_item\",\"payload\":{\"type\":\"function_call_output\",\"call_id\":\"c1\",\"output\":\"{\\\"output\\\":\\\"pom.xml\\\\n\\\",\\\"metadata\\\":{}}\"}}",
                "{\"type\":\"event_msg\",\"payload\":{\"type\":\"token_count\"}}",
                "{\"type\":\"response_item\",\"payload\":{\"type\":\"message\",\"role\":\"assistant\",\"content\":[{\"type\":\"output_text\",\"text\":\"README added.\"}]}}"
        ));
        write(home.resolve(".codex/sessions/2026/09/02/rollout-2026-09-02T10-00-00-bbb.jsonl"), String.join("\n",
                "{\"type\":\"session_meta\",\"payload\":{\"id\":\"bbb\",\"cwd\":\"/some/other/project\"}}",
                "{\"type\":\"response_item\",\"payload\":{\"type\":\"message\",\"role\":\"user\",\"content\":[{\"type\":\"input_text\",\"text\":\"Other project\"}]}}"
        ));

        ExternalSessionImporter importer = new ExternalSessionImporter(home);
        List<ExternalSessionRef> refs = importer.findSessions(CodingAgent.CODEX, project);
        assertEquals(1, refs.size());
        assertEquals("Add a README", refs.get(0).title());
        assertEquals(2, importer.findSessions(CodingAgent.CODEX, null).size(), "null project lists every session");

        AgentSession s = importer.load(refs.get(0));
        assertEquals("codex-aaa", s.getId());
        assertEquals("gpt-5-codex", s.getModelId());
        List<AgentRole> roles = s.getMessages().stream().map(AgentMessage::getRole).toList();
        assertEquals(List.of(AgentRole.USER, AgentRole.THINKING, AgentRole.TOOL_CALL, AgentRole.TOOL, AgentRole.ASSISTANT), roles);
        assertEquals("shell", s.getMessages().get(3).getToolName());
        assertEquals("pom.xml\n", s.getMessages().get(3).getContent());
    }

    @Test
    @DisplayName("Transcript export renders roles, tool calls and metadata as Markdown")
    void transcript() {
        AgentSession s = new AgentSession("Demo", "ANTHROPIC", "claude", 200_000);
        s.addMessage(new AgentMessage(AgentRole.USER, "Hello"));
        s.addMessage(new AgentMessage(AgentRole.TOOL_CALL, "{\"cmd\":\"ls\"}", "bash"));
        s.addMessage(new AgentMessage(AgentRole.TOOL, "file.txt", "bash"));
        s.addMessage(new AgentMessage(AgentRole.ASSISTANT, "Done"));
        String md = SessionTranscriptExporter.toMarkdown(s, false);
        assertTrue(md.startsWith("# Demo"));
        assertTrue(md.contains("## 🧑 User"));
        assertTrue(md.contains("🔧 Tool call: bash"));
        assertFalse(md.contains("file.txt"), "tool output omitted when not requested");
        assertTrue(SessionTranscriptExporter.toMarkdown(s, true).contains("file.txt"));
    }
}
