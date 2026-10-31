package com.github.axiomate.agentic.ide.ui.components;

import com.github.axiomate.agentic.ide.agent.AgentMessage;
import com.github.axiomate.agentic.ide.agent.AgentRole;
import com.github.axiomate.agentic.ide.agent.AgentRunTracker;
import com.github.axiomate.agentic.ide.agent.AgentTaskContext;
import com.github.axiomate.agentic.ide.agent.ExecutionMode;
import com.github.axiomate.agentic.ide.agent.session.AgentSession;
import com.github.axiomate.agentic.ide.agent.session.SessionManager;
import com.github.axiomate.agentic.ide.agent.session.SessionTask;
import com.github.axiomate.agentic.ide.agent.session.TaskBoard;
import com.github.axiomate.agentic.ide.agent.tools.TaskListTool;
import com.github.axiomate.agentic.ide.ui.IdeActions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.swing.*;
import java.awt.*;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Fork & copy options in the chat, the execution-mode controls, the task list and the Tasks panel. */
class ForkModesAndTasksTest {

    /** Two turns: a prompt and its answer, then a prompt with an image, narration, a tool call and the answer. */
    private static AgentSession conversation(String name) {
        AgentSession s = SessionManager.getInstance().createSession(name, "MOCK", "mock-agent", false);
        s.addMessage(new AgentMessage(AgentRole.USER, "first question"));
        s.addMessage(new AgentMessage(AgentRole.ASSISTANT, "first answer"));
        AgentMessage second = new AgentMessage(AgentRole.USER, "second question");
        second.setAttachments(List.of("shots/screen.png"));
        s.addMessage(second);
        AgentMessage narration = new AgentMessage(AgentRole.ASSISTANT, "Let me check the tests");
        narration.setInterim(true);
        s.addMessage(narration);
        s.addMessage(new AgentMessage(AgentRole.TOOL_CALL, "{\"command\":\"mvn test\"}", "bash"));
        s.addMessage(new AgentMessage(AgentRole.TOOL, "BUILD SUCCESS", "bash"));
        s.addMessage(new AgentMessage(AgentRole.ASSISTANT, "second answer"));
        s.getTasks().add(new SessionTask("Write tests", SessionTask.Status.IN_PROGRESS, "agent"));
        return s;
    }

    @Test
    @DisplayName("Forks keep images, the interim flag and the task list; turn boundaries map prompts to messages")
    void forkModel() {
        AgentSession s = conversation("Fork model");
        s.setExecutionMode(ExecutionMode.MANUAL);
        s.getAllowedTools().add("bash");

        AgentSession all = s.fork("copy");
        assertEquals(7, all.getMessages().size());
        assertEquals(List.of("shots/screen.png"), all.getMessages().get(2).getAttachments(), "images were dropped before");
        assertTrue(all.getMessages().get(3).isInterim(), "narration must stay out of later requests to the model");
        assertNotSame(s.getMessages().get(3), all.getMessages().get(3), "independent copies");
        assertEquals(ExecutionMode.MANUAL, all.getExecutionMode());
        assertEquals("Write tests", all.getTasks().get(0).getTitle());
        assertTrue(all.getAllowedTools().isEmpty(), "\"always allow\" choices are not copied to a fork");

        assertEquals(0, AgentSession.indexOfUserMessage(s.getMessages(), 0));
        assertEquals(2, AgentSession.indexOfUserMessage(s.getMessages(), 1));
        assertEquals(-1, AgentSession.indexOfUserMessage(s.getMessages(), 2));
        assertEquals(2, AgentSession.endOfTurn(s.getMessages(), 0));
        assertEquals(7, AgentSession.endOfTurn(s.getMessages(), 1));
        assertEquals(2, s.forkUpTo(2, "first turn").getMessages().size());

        AgentSession fork = SessionManager.getInstance().forkSession(s.getId(), 2, null);
        assertEquals("Fork model (fork)", fork.getName());
        assertSame(fork, SessionManager.getInstance().getActiveSession(), "the fork opens");
        assertEquals(7, s.getMessages().size(), "the original is unchanged");
    }

    private static List<AIAgentPanel.MessageCard> cards(Container c) {
        List<AIAgentPanel.MessageCard> out = new ArrayList<>();
        for (Component k : c.getComponents()) {
            if (k instanceof AIAgentPanel.MessageCard m) out.add(m);
            else if (k instanceof Container cc) out.addAll(cards(cc));
        }
        return out;
    }

    private static JMenuItem item(JPopupMenu menu, String text) {
        for (Component c : menu.getComponents()) {
            if (c instanceof JMenuItem i && text.equals(i.getText())) return i;
        }
        fail("no menu item '" + text + "'");
        return null;
    }

    private static <T extends Component> T find(Container c, Class<T> type, java.util.function.Predicate<T> test) {
        for (Component k : c.getComponents()) {
            if (type.isInstance(k) && test.test(type.cast(k))) return type.cast(k);
            if (k instanceof Container cc) {
                T found = find(cc, type, test);
                if (found != null) return found;
            }
        }
        return null;
    }

    private static JTextArea promptBox(Container c) {
        return find(c, JTextArea.class, a -> a.getToolTipText() != null && a.getToolTipText().startsWith("Type your prompt"));
    }

    private static void press(JComponent c, int key, int modifiers) {
        KeyEvent e = new KeyEvent(c, KeyEvent.KEY_PRESSED, System.currentTimeMillis(), modifiers, key, KeyEvent.CHAR_UNDEFINED);
        for (java.awt.event.KeyListener l : c.getKeyListeners()) l.keyPressed(e);
    }

    @Test
    @DisplayName("Chat menu: copy options, fork from an answer, and edit & resend a prompt in a fork")
    void chatForkAndCopy() throws Exception {
        AgentSession original = conversation("Chat fork");
        AIAgentPanel[] holder = new AIAgentPanel[1];
        SwingUtilities.invokeAndWait(() -> holder[0] = new AIAgentPanel(() -> "", new TerminalPanel()));
        AIAgentPanel panel = holder[0];
        SwingUtilities.invokeAndWait(() -> {
            panel.reloadChatFromSession();
            List<AIAgentPanel.MessageCard> cards = cards(panel);
            AIAgentPanel.MessageCard firstAnswer = cards.stream()
                    .filter(c -> "first answer".equals(c.getRawText())).findFirst().orElseThrow();
            assertNotNull(firstAnswer.getComponentPopupMenu(), "every transcript entry has the menu");

            JPopupMenu menu = new JPopupMenu();
            panel.fillMessageMenu(menu, firstAnswer);
            assertTrue(item(menu, "Copy message").isEnabled());
            item(menu, "Copy conversation as Markdown");
            assertEquals("second answer", panel.lastAnswer(), "the newest final answer, not the narration");
            assertTrue(panel.conversationMarkdown().contains("first question") && panel.conversationMarkdown().contains("second answer"));

            item(menu, "Fork from here").doClick();
            AgentSession fork = SessionManager.getInstance().getActiveSession();
            assertNotEquals(original.getId(), fork.getId());
            assertEquals(List.of("first question", "first answer"), fork.getMessages().stream().map(AgentMessage::getContent).toList());

            SessionManager.getInstance().switchSession(original.getId());
            panel.reloadChatFromSession();
            AIAgentPanel.MessageCard secondPrompt = cards(panel).stream()
                    .filter(c -> "second question".equals(c.getRawText())).findFirst().orElseThrow();
            JPopupMenu userMenu = new JPopupMenu();
            panel.fillMessageMenu(userMenu, secondPrompt);
            item(userMenu, "Edit & resend in a fork").doClick();
            AgentSession edit = SessionManager.getInstance().getActiveSession();
            assertEquals("Chat fork (edit)", edit.getName());
            assertEquals(2, edit.getMessages().size(), "the conversation before the edited prompt");
            assertEquals("second question", promptBox(panel).getText(), "the prompt waits in the box to be changed");
            assertEquals(7, original.getMessages().size());
        });
    }

    @Test
    @DisplayName("Mode controls: Shift+Tab cycles the session's mode, /manual and /mode switch it, the hint follows")
    void modeControls() throws Exception {
        AgentSession s = SessionManager.getInstance().createSession("Modes UI", "MOCK", "mock-agent", false);
        assertEquals(ExecutionMode.AUTO, s.getExecutionMode());
        AIAgentPanel[] holder = new AIAgentPanel[1];
        SwingUtilities.invokeAndWait(() -> holder[0] = new AIAgentPanel(() -> "", new TerminalPanel()));
        AIAgentPanel panel = holder[0];
        SwingUtilities.invokeAndWait(() -> {
            JTextArea input = promptBox(panel);
            @SuppressWarnings("unchecked")
            JComboBox<ExecutionMode> combo = find(panel, JComboBox.class, c -> c.getItemCount() == 3 && c.getItemAt(0) == ExecutionMode.PLAN);
            assertNotNull(combo);

            press(input, KeyEvent.VK_TAB, InputEvent.SHIFT_DOWN_MASK);
            assertEquals(ExecutionMode.PLAN, s.getExecutionMode());
            assertEquals(ExecutionMode.PLAN, combo.getSelectedItem());
            JLabel hint = find(panel, JLabel.class, l -> l.getText() != null && l.getText().startsWith("📝 Plan mode"));
            assertNotNull(hint);
            assertTrue(hint.isVisible());

            input.setText("/manual");
            press(input, KeyEvent.VK_ENTER, InputEvent.CTRL_DOWN_MASK); // a local command: no model call
            assertEquals(ExecutionMode.MANUAL, s.getExecutionMode());
            input.setText("/mode auto");
            press(input, KeyEvent.VK_ENTER, InputEvent.CTRL_DOWN_MASK);
            assertEquals(ExecutionMode.AUTO, s.getExecutionMode());
            assertFalse(hint.isVisible(), "no hint in Auto mode");

            combo.setSelectedItem(ExecutionMode.PLAN);
            assertEquals(ExecutionMode.PLAN, s.getExecutionMode(), "the selector changes the session's mode");
        });
    }

    @Test
    @DisplayName("Plan answers become tasks (not the code block's numbering) and the chat can reopen the plan window")
    void planBecomesTasks() throws Exception {
        AgentSession s = SessionManager.getInstance().createSession("Plan tasks", "MOCK", "mock-agent", false);
        String plan = """
                Summary of the change.

                1. **Add the field** to `AgentSession`
                   1. nested detail, not a step
                2) Write the tests
                ```
                3. not a step either
                ```
                10. Update the README
                """;
        assertEquals(List.of("Add the field to AgentSession", "Write the tests", "Update the README"), TaskBoard.stepsFromPlan(plan));

        AIAgentPanel[] holder = new AIAgentPanel[1];
        SwingUtilities.invokeAndWait(() -> holder[0] = new AIAgentPanel(() -> "", new TerminalPanel()));
        SwingUtilities.invokeAndWait(() -> {
            holder[0].appendPlanActions(s.getId(), plan);
            assertEquals(3, s.getTasks().size());
            assertTrue(s.getTasks().stream().allMatch(t -> t.getStatus() == SessionTask.Status.PENDING && "agent".equals(t.getSource())));
            assertNotNull(find(holder[0], JButton.class, b -> "Open plan window".equals(b.getText())),
                    "the chat's note reopens the plan window, where the implement buttons are");

            holder[0].appendPlanActions(s.getId(), "1. Something else");
            assertEquals(3, s.getTasks().size(), "an open task list is not replaced");
        });
    }

    @Test
    @DisplayName("task_list tool: set, start, complete, rename and list the running task's session")
    void taskListTool() throws Exception {
        AgentSession session = SessionManager.getInstance().createSession("Tool tasks", "MOCK", "mock-agent", false);
        AgentSession other = SessionManager.getInstance().createSession("Other", "MOCK", "mock-agent", false);
        TaskListTool tool = new TaskListTool();
        AgentTaskContext.set(session); // the agent works on its own session even if the user opened another one
        try {
            String out = tool.execute("{\"action\":\"set\",\"tasks\":[{\"title\":\"Read code\",\"status\":\"completed\"},"
                    + "{\"title\":\"Change it\"},\"Test it\"]}");
            assertTrue(out.startsWith("Task list (1/3 done):"), out);
            assertTrue(out.contains("1. [x] Read code") && out.contains("2. [ ] Change it") && out.contains("3. [ ] Test it"), out);
            assertTrue(tool.execute("{\"action\":\"update\",\"index\":2,\"status\":\"in progress\"}").contains("2. [~] Change it"));
            tool.execute("{\"action\":\"complete\",\"index\":2}");
            tool.execute("{\"action\":\"update\",\"index\":3,\"title\":\"Run the tests\"}");
            assertTrue(tool.execute("{\"action\":\"add\",\"title\":\"Update docs\"}").contains("4. [ ] Update docs"));
            assertTrue(tool.execute("{\"action\":\"update\",\"index\":9,\"status\":\"done\"}").startsWith("ERROR"));
            assertTrue(tool.execute("{\"action\":\"update\",\"index\":1,\"status\":\"maybe\"}").startsWith("ERROR"));
            assertTrue(tool.execute("{\"action\":\"fly\"}").startsWith("ERROR"));
        } finally {
            AgentTaskContext.clear();
        }
        assertEquals(List.of("Read code", "Change it", "Run the tests", "Update docs"),
                session.getTasks().stream().map(SessionTask::getTitle).toList());
        assertArrayEquals(new int[]{2, 4}, TaskBoard.progress(session));
        assertTrue(other.getTasks().isEmpty());
    }

    @Test
    @DisplayName("Tasks panel: lists the session's tasks, ticking one completes it, tasks can be added")
    void tasksPanel() throws Exception {
        AgentSession s = SessionManager.getInstance().createSession("Panel tasks", "MOCK", "mock-agent", false);
        TaskBoard.getInstance().replace(s, new ArrayList<>(List.of(
                new SessionTask("Plan", SessionTask.Status.COMPLETED, "agent"),
                new SessionTask("Build", SessionTask.Status.IN_PROGRESS, "agent"))));
        List<String> sent = new ArrayList<>();
        TasksPanel[] holder = new TasksPanel[1];
        SwingUtilities.invokeAndWait(() -> {
            holder[0] = new TasksPanel(IdeActions.NONE);
            holder[0].setPromptSender(sent::add);
        });
        TasksPanel panel = holder[0];
        SwingUtilities.invokeAndWait(() -> {
            assertEquals(List.of("[x] Plan", "[~] Build"), panel.listedTasks());
            JCheckBox build = find(panel, JCheckBox.class, b -> !b.isSelected());
            build.doClick();
            assertEquals(SessionTask.Status.COMPLETED, s.getTasks().get(1).getStatus());

            panel.addTask("Ship it");
            assertEquals("user", s.getTasks().get(2).getSource());
            assertEquals(List.of("[x] Plan", "[x] Build", "[ ] Ship it"), panel.listedTasks());

            JButton next = find(panel, JButton.class, b -> "Continue with next task".equals(b.getText()));
            next.doClick();
            assertEquals(List.of(TasksPanel.CONTINUE_PROMPT), sent);
            assertEquals("42s", TasksPanel.format(java.time.Duration.ofSeconds(42)));
            assertEquals("2m 5s", TasksPanel.format(java.time.Duration.ofSeconds(125)));
        });
    }

    @Test
    @DisplayName("Agent runs: running, tool calls, completion; a new run stops a stale one; first outcome wins")
    void runTracker() {
        AgentRunTracker t = AgentRunTracker.getInstance();
        AgentRunTracker.Run a = t.start("s1", "Session one", "first prompt\nwith lines", ExecutionMode.PLAN);
        assertSame(a, t.running());
        assertEquals("first prompt with lines", a.prompt());
        t.toolCall(a, "search_files");
        t.toolCall(a, "file_system");
        assertEquals(2, a.toolCalls());
        assertEquals("file_system", a.lastTool());

        AgentRunTracker.Run b = t.start("s1", "Session one", "second", ExecutionMode.AUTO);
        assertEquals(AgentRunTracker.Status.STOPPED, a.status(), "only one task runs at a time");
        t.finish(b, AgentRunTracker.Status.COMPLETED, null);
        t.finish(b, AgentRunTracker.Status.FAILED, "late error");
        assertEquals(AgentRunTracker.Status.COMPLETED, b.status());
        assertNull(t.running());
        assertSame(b, t.runs().get(0), "newest first");
        t.toolCall(b, "bash");
        assertEquals(0, b.toolCalls(), "a finished run does not count more calls");
    }
}
