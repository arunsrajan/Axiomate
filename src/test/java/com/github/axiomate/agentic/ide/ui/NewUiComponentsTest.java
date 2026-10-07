package com.github.axiomate.agentic.ide.ui;

import com.github.axiomate.agentic.ide.agent.memory.MemoryItem;
import com.github.axiomate.agentic.ide.mcp.McpServerConfig;
import com.github.axiomate.agentic.ide.plugins.PluginHost;
import com.github.axiomate.agentic.ide.plugins.PluginManager;
import com.github.axiomate.agentic.ide.plugins.SlashCommandRegistry;
import com.github.axiomate.agentic.ide.ui.components.AIAgentPanel;
import com.github.axiomate.agentic.ide.ui.components.ActivityBar;
import com.github.axiomate.agentic.ide.ui.components.AgentSyncPanel;
import com.github.axiomate.agentic.ide.ui.components.PluginsPanel;
import com.github.axiomate.agentic.ide.ui.components.SessionsPanel;
import com.github.axiomate.agentic.ide.ui.components.StatusBar;
import com.github.axiomate.agentic.ide.ui.components.TerminalPanel;
import com.github.axiomate.agentic.ide.ui.dialogs.CommandPalette;
import com.github.axiomate.agentic.ide.ui.util.UIUtils;
import com.github.axiomate.agentic.ide.ui.util.WrapLayout;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.swing.*;
import java.awt.*;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class NewUiComponentsTest {

    @BeforeAll
    static void headless() {
        System.setProperty("java.awt.headless", "true");
    }

    private static final PluginHost NO_OP_HOST = new PluginHost() {
        public void addMemories(List<MemoryItem> items) { }
        public int removeMemoriesBySource(String source) { return 0; }
        public boolean hasMcpServer(String name) { return false; }
        public void addMcpServer(McpServerConfig config) { }
        public void removeMcpServer(String name) { }
        public void setMcpServerEnabled(String name, boolean enabled) { }
        public SlashCommandRegistry commands() { return new SlashCommandRegistry(); }
    };

    @Test
    @DisplayName("Sidebar views, status bar and activity bar construct headlessly")
    void panelsConstruct(@TempDir Path tmp) {
        assertDoesNotThrow(() -> new SessionsPanel(IdeActions.NONE));
        assertDoesNotThrow(() -> new AgentSyncPanel(IdeActions.NONE));
        assertDoesNotThrow(() -> new PluginsPanel(IdeActions.NONE, new PluginManager(tmp.resolve("plugins"), NO_OP_HOST)));
        StatusBar bar = new StatusBar();
        bar.setPluginCount(2);

        AtomicReference<String> selected = new AtomicReference<>("unset");
        ActivityBar activity = new ActivityBar(selected::set);
        activity.addView("a", "A", UIUtils.Glyph.EXPLORER);
        activity.addView("b", "B", UIUtils.Glyph.SESSIONS);
        activity.select("a");
        assertEquals("a", activity.getSelectedId());
        ((AbstractButton) findAll(activity, JToggleButton.class).get(1)).doClick();
        assertEquals("b", selected.get());
        ((AbstractButton) findAll(activity, JToggleButton.class).get(1)).doClick();
        assertNull(selected.get(), "clicking the active view collapses the sidebar");
    }

    @Test
    @DisplayName("Theme palette adapts to light and dark look-and-feels")
    void palette() {
        UIUtils.applyTheme("IntelliJ Light", null);
        assertFalse(UIUtils.isDark());
        Color lightFg = UIUtils.foreground();
        UIUtils.applyTheme("FlatLaf Darcula", null);
        assertTrue(UIUtils.isDark());
        assertNotEquals(lightFg, UIUtils.foreground());
        assertEquals(new Color(0, 0, 0), UIUtils.blend(Color.BLACK, Color.WHITE, 0f));
        assertEquals(new Color(255, 255, 255), UIUtils.blend(Color.BLACK, Color.WHITE, 1f));
    }

    @Test
    @DisplayName("WrapLayout reports extra rows when components do not fit")
    void wrapLayout() {
        JPanel p = new JPanel(new WrapLayout(FlowLayout.LEFT, 0, 0));
        for (int i = 0; i < 4; i++) {
            JLabel l = new JLabel("x");
            l.setPreferredSize(new Dimension(100, 20));
            p.add(l);
        }
        p.setSize(250, 100);
        assertEquals(40, p.getPreferredSize().height, "4 × 100px items in 250px wrap into 2 rows");
    }

    @Test
    @DisplayName("Command palette fuzzy matching prefers substrings and word starts")
    void fuzzyScore() {
        assertTrue(CommandPalette.score("sess", "view › show sessions sidebar") > 0);
        assertTrue(CommandPalette.score("imp mem", "ai agent › import memory") > 0);
        assertEquals(-1, CommandPalette.score("xyz", "open project"));
        assertTrue(CommandPalette.score("plugin", "plugins › plugin manager")
                > CommandPalette.score("plugin", "p l u g i n scattered"));
    }

    @Test
    @DisplayName("Agent chat runs built-in slash commands and reports unknown ones")
    void slashCommandsInChat() {
        AIAgentPanel panel = new AIAgentPanel(() -> "", new TerminalPanel());
        panel.sendPromptDirectly("/help");
        String text = allText(panel);
        assertTrue(text.contains("Available slash commands"), "help lists commands");
        assertTrue(text.contains("/clear"));

        panel.sendPromptDirectly("/definitely-not-a-command");
        assertTrue(allText(panel).contains("Unknown command: /definitely-not-a-command"));
    }

    private static String allText(Container c) {
        StringBuilder sb = new StringBuilder();
        for (JTextArea a : findAll(c, JTextArea.class)) sb.append(a.getText()).append('\n');
        return sb.toString();
    }

    private static <T> List<T> findAll(Container c, Class<T> type) {
        List<T> out = new ArrayList<>();
        for (Component child : c.getComponents()) {
            if (type.isInstance(child)) out.add(type.cast(child));
            if (child instanceof Container cc) out.addAll(findAll(cc, type));
        }
        return out;
    }
}
