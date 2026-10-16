package com.github.axiomate.agentic.ide.ui.components;

import com.github.axiomate.agentic.ide.agent.AgentMessage;
import com.github.axiomate.agentic.ide.agent.session.AgentSession;
import com.github.axiomate.agentic.ide.agent.session.SessionManager;
import com.github.axiomate.agentic.ide.util.ProjectManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.swing.*;
import java.awt.*;
import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class MarkdownTableTest {

    @TempDir
    Path dir;
    private File previousProject;

    @BeforeEach
    void setup() {
        previousProject = ProjectManager.getInstance().getCurrentProjectDirectory();
        ProjectManager.getInstance().setCurrentProjectDirectory(dir.toFile());
    }

    @AfterEach
    void tearDown() {
        if (previousProject != null) {
            ProjectManager.getInstance().setCurrentProjectDirectory(previousProject);
        }
    }

    private static void flushEdt() throws Exception {
        SwingUtilities.invokeAndWait(() -> { });
        SwingUtilities.invokeAndWait(() -> { });
    }

    @Test
    @DisplayName("MarkdownTableParser correctly parses standard GFM tables and alignments")
    void testParserBasicAndAlignments() {
        String md = """
                Here is a summary:

                | Name | Age | City | Score |
                | :--- | :---: | ---: | --- |
                | Alice | 30 | New York | 95.5 |
                | Bob | 25 | London | 88.0 |

                Done.
                """;

        assertTrue(MarkdownTableParser.containsTable(md));
        List<MarkdownTableParser.Segment> segments = MarkdownTableParser.parseSegments(md);
        assertEquals(3, segments.size());

        assertInstanceOf(MarkdownTableParser.TextSegment.class, segments.get(0));
        assertTrue(((MarkdownTableParser.TextSegment) segments.get(0)).text().contains("Here is a summary:"));

        assertInstanceOf(MarkdownTableParser.TableSegment.class, segments.get(1));
        MarkdownTableParser.TableSegment table = (MarkdownTableParser.TableSegment) segments.get(1);

        assertEquals(List.of("Name", "Age", "City", "Score"), table.headers());
        assertEquals(List.of(SwingConstants.LEFT, SwingConstants.CENTER, SwingConstants.RIGHT, SwingConstants.LEFT),
                table.alignments());
        assertEquals(2, table.rows().size());
        assertEquals(List.of("Alice", "30", "New York", "95.5"), table.rows().get(0));
        assertEquals(List.of("Bob", "25", "London", "88.0"), table.rows().get(1));

        assertInstanceOf(MarkdownTableParser.TextSegment.class, segments.get(2));
        assertTrue(((MarkdownTableParser.TextSegment) segments.get(2)).text().contains("Done."));

        // Test TSV conversion
        String tsv = table.toTsv();
        assertTrue(tsv.contains("Name\tAge\tCity\tScore"));
        assertTrue(tsv.contains("Alice\t30\tNew York\t95.5"));
    }

    @Test
    @DisplayName("MarkdownTableParser handles escaped pipes and uneven columns")
    void testEscapedPipesAndPadding() {
        String md = """
                | Operator | Description |
                | --- | --- |
                | `a \\| b` | Bitwise OR |
                | `a & b` |
                | extra | col | here |
                """;

        List<MarkdownTableParser.Segment> segments = MarkdownTableParser.parseSegments(md);
        assertEquals(1, segments.size());
        assertInstanceOf(MarkdownTableParser.TableSegment.class, segments.get(0));

        MarkdownTableParser.TableSegment table = (MarkdownTableParser.TableSegment) segments.get(0);
        assertEquals(List.of("Operator", "Description"), table.headers());
        assertEquals(3, table.rows().size());

        // Escaped pipe preserved without splitting
        assertEquals(List.of("`a | b`", "Bitwise OR"), table.rows().get(0));
        // Missing column padded with empty string
        assertEquals(List.of("`a & b`", ""), table.rows().get(1));
        // Extra column clamped to column count
        assertEquals(List.of("extra", "col"), table.rows().get(2));
    }

    @Test
    @DisplayName("MarkdownTableParser ignores tables inside fenced code blocks")
    void testCodeFenceIgnored() {
        String md = """
                Code sample:
                ```markdown
                | Header 1 | Header 2 |
                | --- | --- |
                | val1 | val2 |
                ```
                After code.
                """;

        List<MarkdownTableParser.Segment> segments = MarkdownTableParser.parseSegments(md);
        assertTrue(segments.stream().noneMatch(s -> s instanceof MarkdownTableParser.TableSegment),
                "Code blocks should not produce TableSegment");
        assertTrue(segments.stream().anyMatch(s -> s instanceof MarkdownTableParser.CodeBlockSegment c
                && c.code().contains("| Header 1 |")));
    }

    @Test
    @DisplayName("MarkdownTablePanel builds JTable with correct structure and cell values")
    void testMarkdownTablePanelComponent() throws Exception {
        MarkdownTableParser.TableSegment segment = new MarkdownTableParser.TableSegment(
                List.of("Framework", "Language", "Stars"),
                List.of(SwingConstants.LEFT, SwingConstants.CENTER, SwingConstants.RIGHT),
                List.of(
                        List.of("Spring", "Java", "55k"),
                        List.of("LangChain4j", "Java", "6k")
                ),
                "| Framework | Language | Stars |\n|---|---|---|\n| Spring | Java | 55k |\n| LangChain4j | Java | 6k |"
        );

        MarkdownTablePanel[] panel = new MarkdownTablePanel[1];
        SwingUtilities.invokeAndWait(() -> panel[0] = new MarkdownTablePanel(segment));
        flushEdt();

        JTable table = panel[0].getTable();
        assertNotNull(table);
        assertEquals(3, table.getColumnCount());
        assertEquals(2, table.getRowCount());
        assertEquals("Framework", table.getColumnName(0));
        assertEquals("Stars", table.getColumnName(2));
        assertEquals("Spring", table.getValueAt(0, 0));
        assertEquals("6k", table.getValueAt(1, 2));
        assertFalse(table.isCellEditable(0, 0));
    }

    @Test
    @DisplayName("AIAgentPanel renders MarkdownTablePanel when assistant responds with a table")
    void testAIAgentPanelRendersTable() throws Exception {
        AIAgentPanel[] holder = new AIAgentPanel[1];
        SwingUtilities.invokeAndWait(() -> holder[0] = new AIAgentPanel(() -> "", new TerminalPanel()));
        AIAgentPanel panel = holder[0];
        flushEdt();

        String responseWithTable = """
                Here are the results of the performance benchmarks:

                | Test Case | Execution Time | Memory Used | Status |
                | :--- | :---: | :---: | ---: |
                | Quick Sort | 14ms | 4.2 MB | Passed |
                | Merge Sort | 18ms | 6.1 MB | Passed |
                | Heap Sort | 22ms | 5.8 MB | Passed |

                All sorting algorithms passed with optimal complexity.
                """;

        AgentSession session = SessionManager.getInstance().createSession("Table Test Session", "MOCK", "mock-agent", false);
        session.addMessage(new AgentMessage(com.github.axiomate.agentic.ide.agent.AgentRole.USER, "Run sorting benchmarks", null));
        session.addMessage(new AgentMessage(com.github.axiomate.agentic.ide.agent.AgentRole.ASSISTANT, responseWithTable, null));

        SwingUtilities.invokeAndWait(panel::reloadChatFromSession);
        flushEdt();

        // Verify that MarkdownTablePanel and JTable are present in the transcript
        List<MarkdownTablePanel> tablePanels = findAll(panel, MarkdownTablePanel.class);
        assertEquals(1, tablePanels.size(), "Should contain exactly one MarkdownTablePanel");

        JTable table = tablePanels.get(0).getTable();
        assertEquals(4, table.getColumnCount());
        assertEquals(3, table.getRowCount());
        assertEquals("Quick Sort", table.getValueAt(0, 0));
        assertEquals("Passed", table.getValueAt(2, 3));

        // Verify text areas before and after table
        List<JTextArea> textAreas = findAll(panel, JTextArea.class);
        boolean hasIntro = textAreas.stream().anyMatch(a -> a.getText().contains("Here are the results"));
        boolean hasOutro = textAreas.stream().anyMatch(a -> a.getText().contains("All sorting algorithms passed"));
        assertTrue(hasIntro, "Transcript should contain text before the table");
        assertTrue(hasOutro, "Transcript should contain text after the table");
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
