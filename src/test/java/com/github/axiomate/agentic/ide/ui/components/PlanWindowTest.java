package com.github.axiomate.agentic.ide.ui.components;

import com.github.axiomate.agentic.ide.agent.ExecutionMode;
import com.github.axiomate.agentic.ide.agent.session.AgentSession;
import com.github.axiomate.agentic.ide.agent.session.SessionManager;
import com.github.axiomate.agentic.ide.agent.session.SessionTask;
import com.github.axiomate.agentic.ide.config.ConfigManager;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.swing.*;
import java.awt.*;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/** The implementation plan window: its prompts, steps, edit and revise controls, and how the chat opens it. */
class PlanWindowTest {

    static final String PLAN = """
            Add a cache in front of the client.

            1. Add `CacheConfig`
            2. Wrap the client in `CachingClient`
            3. Write tests

            Risks: stale entries.
            """;

    /** Records what the window asks the IDE to do. */
    static final class Recorder implements ImplementationPlanPanel.Actions {
        final List<String> calls = new ArrayList<>();
        final AtomicReference<String> prompt = new AtomicReference<>();
        final AtomicReference<List<String>> steps = new AtomicReference<>();

        public void implement(ExecutionMode mode, String p, List<String> s) {
            calls.add("implement " + mode.name());
            prompt.set(p);
            steps.set(s);
        }

        public void revise(String p) {
            calls.add("revise");
            prompt.set(p);
        }

        public void showTasks() {
            calls.add("tasks");
        }
    }

    @Test
    @DisplayName("Prompts: an unchanged plan is referred to, a partial one names its steps, an edited one is sent")
    void prompts() {
        List<String> steps = List.of("Add CacheConfig", "Wrap the client", "Write tests");
        assertEquals(ImplementationPlanPanel.IMPLEMENT_PROMPT,
                ImplementationPlanPanel.implementationPrompt(PLAN, false, steps, List.of(0, 1, 2)));

        String partial = ImplementationPlanPanel.implementationPrompt(PLAN, false, steps, List.of(0, 2));
        assertTrue(partial.contains("Do only these steps and skip the others:\n1. Add CacheConfig\n3. Write tests"), partial);
        assertFalse(partial.contains("Wrap the client"));

        String edited = ImplementationPlanPanel.implementationPrompt("1. Only this", true, List.of("Only this"), List.of(0));
        assertTrue(edited.startsWith("Implement this plan (the user edited it)"), edited);
        assertTrue(edited.endsWith("1. Only this"), "the edited plan is sent in full");

        String revise = ImplementationPlanPanel.revisionPrompt("  use Caffeine  ", "1. Edited", true);
        assertTrue(revise.startsWith("Revise the implementation plan. Feedback: use Caffeine"), revise);
        assertTrue(revise.contains("Start from this edited version of the plan:\n\n1. Edited"));
        assertFalse(ImplementationPlanPanel.revisionPrompt("x", PLAN, false).contains("edited version"));

        // Steps read as plain text in the window and the Tasks panel; identifiers keep their underscores
        assertEquals(List.of("Rename old_name to new_name in the menu", "Keep snake_case_names"),
                com.github.axiomate.agentic.ide.agent.session.TaskBoard.stepsFromPlan(
                        "1. Rename *old_name* to `new_name` in _the_ **menu**\n2. Keep snake_case_names"));
    }

    @Test
    @DisplayName("Rendered Markdown keeps its styles to itself instead of changing the IDE-wide HTML style sheet")
    void markdownStylesStayLocal() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            javax.swing.text.html.StyleSheet shared = new javax.swing.text.html.HTMLEditorKit().getStyleSheet();
            MarkdownTextPanel a = new MarkdownTextPanel("Run `mvn` to **build**");
            new MarkdownTextPanel("## Heading\n- item");
            assertNull(shared.getRule("code").getAttribute(javax.swing.text.html.CSS.Attribute.BACKGROUND_COLOR),
                    "the chat's code style must not leak into every other HTML view (labels, dialogs)");
            assertNull(shared.getRule("p").getAttribute(javax.swing.text.html.CSS.Attribute.COLOR),
                    "nor its theme colour for paragraphs");
            javax.swing.text.html.HTMLDocument doc = (javax.swing.text.html.HTMLDocument) a.getEditorPane().getDocument();
            assertNotNull(doc.getStyleSheet().getRule("code").getAttribute(javax.swing.text.html.CSS.Attribute.BACKGROUND_COLOR),
                    "the panel's own document has the code style");
        });
    }

    @Test
    @DisplayName("Steps: listed with checkboxes; unticked ones are left out and the window closes after implementing")
    void stepsAndImplement() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            ImplementationPlanPanel panel = new ImplementationPlanPanel("s1", "Cache work", PLAN);
            Recorder rec = new Recorder();
            List<String> closed = new ArrayList<>();
            panel.setActions(rec);
            panel.setCloseHandler(() -> closed.add("closed"));

            assertEquals(List.of("Add CacheConfig", "Wrap the client in CachingClient", "Write tests"), panel.steps());
            assertTrue(panel.implementAuto.isEnabled() && panel.implementManual.isEnabled());
            assertFalse(panel.reviseBtn.isEnabled(), "nothing to revise without feedback");

            panel.setStepSelected(1, false);
            assertEquals(List.of("Add CacheConfig", "Write tests"), panel.selectedSteps());
            panel.implementManual.doClick();
            assertEquals(List.of("implement MANUAL"), rec.calls);
            assertTrue(rec.prompt.get().contains("Do only these steps"), rec.prompt.get());
            assertEquals(List.of("Add CacheConfig", "Write tests"), rec.steps.get(), "the chosen steps become the task list");
            assertEquals(List.of("closed"), closed);

            for (int i = 0; i < 3; i++) panel.setStepSelected(i, false);
            assertFalse(panel.implementAuto.isEnabled(), "at least one step must be ticked");
            assertFalse(panel.implementManual.isEnabled());

            panel.tasksBtn.doClick();
            assertTrue(rec.calls.contains("tasks"));
        });
    }

    @Test
    @DisplayName("Edit: the Markdown can be changed; steps follow the edit and keep what was unticked")
    void editPlan() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            ImplementationPlanPanel panel = new ImplementationPlanPanel("s1", "Cache work", PLAN);
            Recorder rec = new Recorder();
            panel.setActions(rec);
            panel.setStepSelected(2, false);

            panel.editToggle.doClick();
            assertEquals("Preview", panel.editToggle.getText());
            JTextArea editor = panel.editor;
            editor.setText(PLAN.replace("3. Write tests", "3. Write tests\n4. Update the README"));
            assertTrue(panel.isEdited());
            assertEquals(4, panel.steps().size());
            assertEquals(List.of("Add CacheConfig", "Wrap the client in CachingClient", "Update the README"),
                    panel.selectedSteps(), "the unticked step stays unticked");
            panel.editToggle.doClick();
            assertEquals("Edit", panel.editToggle.getText());

            panel.implementAuto.doClick();
            assertEquals(List.of("implement AUTO"), rec.calls);
            assertTrue(rec.prompt.get().contains("4. Update the README"), "the edited plan is sent");
        });
    }

    @Test
    @DisplayName("Revise: feedback is sent in Plan mode, the window waits, and the revised plan replaces the old one")
    void revise() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            ImplementationPlanPanel panel = new ImplementationPlanPanel("s1", "Cache work", PLAN);
            Recorder rec = new Recorder();
            panel.setActions(rec);
            panel.feedbackField.setText("Use Caffeine instead");
            assertTrue(panel.reviseBtn.isEnabled());
            panel.reviseBtn.doClick();
            assertEquals(List.of("revise"), rec.calls);
            assertTrue(rec.prompt.get().contains("Feedback: Use Caffeine instead"));
            assertTrue(panel.isBusy());
            assertFalse(panel.implementAuto.isEnabled(), "no implementing while the plan is being revised");

            panel.setPlan("Cache work", "1. Add Caffeine\n2. Write tests");
            assertFalse(panel.isBusy());
            assertEquals(List.of("Add Caffeine", "Write tests"), panel.steps());
            assertEquals("", panel.feedbackField.getText());
            assertFalse(panel.isEdited());
        });
    }

    @Test
    @DisplayName("Chat: a plan is kept with the session and opens its window; the window's actions switch mode and set tasks")
    void chatOpensThePlanWindow() throws Exception {
        AgentSession s = SessionManager.getInstance().createSession("Plan window", "MOCK", "mock-agent", false);
        s.setExecutionMode(ExecutionMode.PLAN);
        List<String> opened = new ArrayList<>();
        AtomicReference<ImplementationPlanPanel.Actions> actions = new AtomicReference<>();
        List<String> sent = new ArrayList<>();
        AIAgentPanel[] holder = new AIAgentPanel[1];
        SwingUtilities.invokeAndWait(() -> {
            holder[0] = new AIAgentPanel(() -> "", new TerminalPanel());
            holder[0].setPlanWindowOpener((session, a) -> {
                opened.add(session.getId());
                actions.set(a);
            });
            holder[0].setPlanPromptSender(sent::add);
        });
        AIAgentPanel panel = holder[0];
        boolean autoOpen = ConfigManager.getInstance().getConfig().isOpenPlanWindowAutomatically();
        try {
            SwingUtilities.invokeAndWait(() -> {
                panel.onPlanReady(s.getId(), PLAN, true);
                assertEquals(PLAN.strip(), s.getImplementationPlan(), "kept with the session for reopening and restarts");
                assertEquals(List.of(s.getId()), opened, "the plan window opens when the plan arrives");
                assertEquals(3, s.getTasks().size());

                JButton reopen = find(panel, JButton.class, b -> "Open plan window".equals(b.getText()));
                reopen.doClick();
                assertEquals(2, opened.size());

                actions.get().implement(ExecutionMode.MANUAL, "do it", List.of("Only step"));
                assertEquals(ExecutionMode.MANUAL, s.getExecutionMode());
                assertEquals(List.of("Only step"), s.getTasks().stream().map(SessionTask::getTitle).toList());
                assertEquals(List.of("do it"), sent);

                actions.get().revise("revise it");
                assertEquals(ExecutionMode.PLAN, s.getExecutionMode(), "a revision is planned, not carried out");
                assertEquals(List.of("do it", "revise it"), sent);

                ConfigManager.getInstance().getConfig().setOpenPlanWindowAutomatically(false);
                panel.onPlanReady(s.getId(), "1. Next plan", false);
                assertEquals(2, opened.size(), "with the setting off the window is not opened by itself");
                assertEquals("1. Next plan", s.getImplementationPlan());
            });
        } finally {
            ConfigManager.getInstance().getConfig().setOpenPlanWindowAutomatically(autoOpen);
        }

        AgentSession fork = s.fork("fork");
        assertEquals("1. Next plan", fork.getImplementationPlan(), "forks keep the plan");
    }

    private static <T extends Component> T find(Container c, Class<T> type) {
        return find(c, type, t -> true);
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
}
