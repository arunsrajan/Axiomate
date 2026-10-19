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
import java.util.Map;

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
    @DisplayName("Imports Google Antigravity transcript JSONL with thoughts, tool calls and outputs")
    void antigravity() throws IOException {
        Path home = Files.createDirectories(tmp.resolve("home"));
        Path project = Files.createDirectories(tmp.resolve("proj"));
        String cwd = project.toFile().getCanonicalPath().replace("\\", "\\\\");
        Path convDir = home.resolve(".gemini/antigravity-ide/brain/conv-123");
        Path transcript = convDir.resolve(".system_generated/logs/transcript.jsonl");

        write(transcript, String.join("\n",
                "{\"step_index\":0,\"source\":\"USER_EXPLICIT\",\"type\":\"USER_INPUT\",\"status\":\"DONE\",\"created_at\":\"2026-09-01T12:00:00Z\",\"content\":\"<USER_REQUEST>\\nOptimize SQL queries\\n</USER_REQUEST>\\n<ADDITIONAL_METADATA>\\nThe user has 1 active workspaces, each defined by a URI and a CorpusName. Multiple URIs potentially map to the same CorpusName. The mapping is shown as follows in the format [URI] -> [CorpusName]:\\n" + cwd + " -> my-project\\n</ADDITIONAL_METADATA>\\n<USER_SETTINGS_CHANGE>\\nThe user changed setting `Model Selection` from None to Gemini 2.5 Pro (High).\\n</USER_SETTINGS_CHANGE>\"}",
                "{\"step_index\":1,\"source\":\"MODEL\",\"type\":\"PLANNER_RESPONSE\",\"status\":\"DONE\",\"created_at\":\"2026-09-01T12:00:05Z\",\"content\":\"Analyzing queries...\",\"thinking\":\"Check explain plan first.\",\"tool_calls\":[{\"name\":\"run_command\",\"args\":{\"CommandLine\":\"explain analyze select * from users\"}}]}",
                "{\"step_index\":2,\"source\":\"MODEL\",\"type\":\"RUN_COMMAND\",\"status\":\"DONE\",\"created_at\":\"2026-09-01T12:00:08Z\",\"content\":\"Seq Scan on users (cost=0.00..35.50 rows=2550)\"}",
                "{\"step_index\":3,\"source\":\"MODEL\",\"type\":\"PLANNER_RESPONSE\",\"status\":\"DONE\",\"created_at\":\"2026-09-01T12:00:10Z\",\"content\":\"Add an index on users(email).\"}"
        ));

        // Another conversation for a different project
        Path otherConv = home.resolve(".gemini/antigravity-ide/brain/conv-other/.system_generated/logs/transcript.jsonl");
        String otherCwd = tmp.resolve("other").toFile().getCanonicalPath().replace("\\", "\\\\");
        write(otherConv, "{\"step_index\":0,\"source\":\"USER_EXPLICIT\",\"type\":\"USER_INPUT\",\"created_at\":\"2026-09-01T11:00:00Z\",\"content\":\"<USER_REQUEST>Other work</USER_REQUEST>\\n" + otherCwd + " -> other\"}\n");

        ExternalSessionImporter importer = new ExternalSessionImporter(home);
        List<ExternalSessionRef> refs = importer.findSessions(CodingAgent.ANTIGRAVITY, project);
        assertEquals(1, refs.size(), "Only the matching project's Antigravity conversation is returned");
        assertEquals("Optimize SQL queries", refs.get(0).title());
        assertEquals("conv-123", refs.get(0).externalId());

        assertEquals(2, importer.findSessions(CodingAgent.ANTIGRAVITY, null).size(), "null project lists all conversations");

        AgentSession session = importer.load(refs.get(0));
        assertEquals("antigravity-conv-123", session.getId());
        assertEquals("GEMINI", session.getProviderId());
        assertEquals("gemini-2.5-pro", session.getModelId());
        assertTrue(session.getOrigin().contains("Google Antigravity"));

        List<AgentRole> roles = session.getMessages().stream().map(AgentMessage::getRole).toList();
        assertEquals(List.of(AgentRole.USER, AgentRole.THINKING, AgentRole.ASSISTANT, AgentRole.TOOL_CALL, AgentRole.TOOL, AgentRole.ASSISTANT), roles);

        assertEquals("Optimize SQL queries", session.getMessages().get(0).getContent());
        assertEquals("Check explain plan first.", session.getMessages().get(1).getContent());
        assertEquals("run_command", session.getMessages().get(3).getToolName());
        assertEquals("Seq Scan on users (cost=0.00..35.50 rows=2550)", session.getMessages().get(4).getContent());
        assertEquals("run command", session.getMessages().get(4).getToolName());
    }

    @Test
    @DisplayName("Imports Google Gemini CLI JSON chat sessions with user, model and tool interactions")
    void geminiCli() throws IOException {
        Path home = Files.createDirectories(tmp.resolve("home"));
        Path project = Files.createDirectories(tmp.resolve("proj"));
        String cwd = project.toFile().getCanonicalPath().replace("\\", "\\\\");
        Path chatFile = home.resolve(".gemini/chats/session-456.json");

        write(chatFile, """
                {
                  "sessionId": "session-456",
                  "cwd": "%s",
                  "timestamp": "2026-09-01T14:00:00Z",
                  "model": "gemini-2.0-flash",
                  "messages": [
                    {"role": "user", "content": "Refactor router handlers", "timestamp": "2026-09-01T14:00:00Z"},
                    {
                      "role": "model",
                      "content": "Refactored handlers into separate module.",
                      "thought": "Group routes by resource name",
                      "timestamp": "2026-09-01T14:00:05Z",
                      "toolCalls": [{"name": "write_file", "args": {"path": "router.js"}}]
                    },
                    {"role": "tool", "name": "write_file", "content": "File saved successfully", "timestamp": "2026-09-01T14:00:08Z"}
                  ]
                }
                """.formatted(cwd));

        ExternalSessionImporter importer = new ExternalSessionImporter(home);
        List<ExternalSessionRef> refs = importer.findSessions(CodingAgent.GEMINI_CLI, project);
        assertEquals(1, refs.size());
        assertEquals("Refactor router handlers", refs.get(0).title());
        assertEquals("session-456", refs.get(0).externalId());

        AgentSession session = importer.load(refs.get(0));
        assertEquals("gemini-cli-session-456", session.getId());
        assertEquals("GEMINI", session.getProviderId());
        assertEquals("gemini-2.0-flash", session.getModelId());
        assertTrue(session.getOrigin().contains("Gemini CLI"));

        List<AgentRole> roles = session.getMessages().stream().map(AgentMessage::getRole).toList();
        assertEquals(List.of(AgentRole.USER, AgentRole.THINKING, AgentRole.ASSISTANT, AgentRole.TOOL_CALL, AgentRole.TOOL), roles);
        assertEquals("Group routes by resource name", session.getMessages().get(1).getContent());
        assertEquals("write_file", session.getMessages().get(3).getToolName());
        assertEquals("File saved successfully", session.getMessages().get(4).getContent());
    }

    @Test
    @DisplayName("Honors CLAUDE_CONFIG_DIR, CODEX_HOME and GEMINI_CLI_HOME environment overrides")
    void environmentOverrides() throws IOException {
        Path customClaude = tmp.resolve("custom-claude");
        Path customCodex = tmp.resolve("custom-codex");
        Path customGemini = tmp.resolve("custom-gemini");
        Path project = Files.createDirectories(tmp.resolve("proj"));
        String cwd = project.toFile().getCanonicalPath().replace("\\", "\\\\");

        write(customClaude.resolve("projects/" + CodingAgentCatalog.encodeClaudeProjectDir(project) + "/c1.jsonl"),
                "{\"type\":\"user\",\"sessionId\":\"c1\",\"message\":{\"role\":\"user\",\"content\":\"Claude custom\"}}\n");
        write(customCodex.resolve("sessions/2026/09/01/rollout-custom.jsonl"),
                "{\"type\":\"session_meta\",\"payload\":{\"id\":\"cdx1\",\"cwd\":\"" + cwd + "\"}}\n{\"type\":\"response_item\",\"payload\":{\"type\":\"message\",\"role\":\"user\",\"content\":[{\"type\":\"input_text\",\"text\":\"Codex custom\"}]}}\n");
        write(customGemini.resolve(".gemini/chats/g1.json"),
                "{\"sessionId\":\"g1\",\"cwd\":\"" + cwd + "\",\"messages\":[{\"role\":\"user\",\"content\":\"Gemini custom\"}]}\n");

        AgentEnvironment env = AgentEnvironment.of(tmp.resolve("default-home"), Map.of(
                AgentEnvironment.CLAUDE_CONFIG_DIR, customClaude.toString(),
                AgentEnvironment.CODEX_HOME, customCodex.toString(),
                AgentEnvironment.GEMINI_CLI_HOME, customGemini.toString()
        ));

        ExternalSessionImporter importer = new ExternalSessionImporter(env);
        List<ExternalSessionRef> all = importer.findAll(project);
        assertEquals(3, all.size());
        assertTrue(all.stream().anyMatch(r -> r.title().equals("Claude custom")));
        assertTrue(all.stream().anyMatch(r -> r.title().equals("Codex custom")));
        assertTrue(all.stream().anyMatch(r -> r.title().equals("Gemini custom")));
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
