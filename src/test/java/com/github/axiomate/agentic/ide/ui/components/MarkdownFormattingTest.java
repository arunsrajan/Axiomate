package com.github.axiomate.agentic.ide.ui.components;

import com.github.axiomate.agentic.ide.agent.AgentMessage;
import com.github.axiomate.agentic.ide.agent.session.AgentSession;
import com.github.axiomate.agentic.ide.agent.session.SessionManager;
import com.github.axiomate.agentic.ide.util.ProjectManager;
import org.fife.ui.rsyntaxtextarea.SyntaxConstants;
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

class MarkdownFormattingTest {

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
    @DisplayName("MarkdownTextPanel converts headings, bold, inline code, and lists to HTML")
    void testMarkdownTextPanelHtmlConversion() {
        String md = """
                Done. The project compiles cleanly (offline `mvn -o compile` returned exit code 0).

                ## What I changed — `src/main/java/AzureBlobStorage.java`

                **1. Added a new download route** (`download-file-from-azure`):

                - `@Slf4j` for class-level logger
                - Camel `.log()` statements
                """;

        assertTrue(MarkdownTextPanel.hasMarkdownFormatting(md));

        String html = MarkdownTextPanel.markdownToHtmlBody(md, "#333", "#fff");
        assertNotNull(html);

        // Verify Double hash (##) converted to <h2>
        assertTrue(html.contains("<h2>What I changed"), "Should render H2 heading for ##");
        assertTrue(html.contains("</h2>"), "Should close H2 tag");

        // Verify Two stars (**) converted to <b>
        assertTrue(html.contains("<b>1. Added a new download route</b>"), "Should render <b> for **");

        // Verify Inline code (`) converted to <code>
        assertTrue(html.contains("<code"), "Should render <code> tag for backticks");
        assertTrue(html.contains("mvn -o compile</code>"), "Should wrap inline code in <code>");

        // Verify Lists (-) converted to <ul> and <li>
        assertTrue(html.contains("<ul>"), "Should open unordered list for - items");
        assertTrue(html.contains("<li>"), "Should wrap list items in <li>");
        assertTrue(html.contains("</ul>"), "Should close unordered list");
    }

    @Test
    @DisplayName("MarkdownCodePanel highlights syntax and maps language correctly")
    void testMarkdownCodePanel() throws Exception {
        String javaSnippet = """
                from("direct:downloadFromAzure")
                    .routeId("download-file-from-azure")
                    .to("file:./camel-out");
                """;
        MarkdownTableParser.CodeBlockSegment segment = new MarkdownTableParser.CodeBlockSegment(
                "java", javaSnippet, "```java\n" + javaSnippet + "```"
        );

        MarkdownCodePanel[] holder = new MarkdownCodePanel[1];
        SwingUtilities.invokeAndWait(() -> holder[0] = new MarkdownCodePanel(segment));
        flushEdt();

        MarkdownCodePanel panel = holder[0];
        assertNotNull(panel);
        assertEquals(SyntaxConstants.SYNTAX_STYLE_JAVA, panel.getCodeArea().getSyntaxEditingStyle());
        assertEquals(javaSnippet, panel.getCodeArea().getText());
        assertFalse(panel.getCodeArea().isEditable());
    }

    @Test
    @DisplayName("AIAgentPanel parses and formats user example message with ##, ```, and **")
    void testUserExampleMessageFormattingInAIAgentPanel() throws Exception {
        String userSampleMessage = """
                Done. The project compiles cleanly (offline `mvn -o compile` returned exit code 0). 

                ## What I changed — `src/main/java/com/example/demo/route/AzureBlobStorage.java`

                **1. Added a new download route** (`download-file-from-azure`):

                ```java
                from("direct:downloadFromAzure")
                    .routeId("download-file-from-azure")
                    .log("Download request received for blob '${header.CamelFileName}'")
                    .toD("azure-storage-blob://camelstorage/camelstorage?accessKey=...&blobName=${header[CamelFileName]}&operation=getBlob&credentialType=SHARED_ACCOUNT_KEY")
                    .log("Downloaded blob '${header.CamelFileName}', writing to local folder './camel-out'")
                    .to("file:./camel-out")
                    .log("Blob '${header.CamelFileName}' saved to local folder './camel-out'");
                ```

                It uses the `getBlob` operation to fetch a blob from Azure, then writes it to the local **`./camel-out`** folder (the file name is preserved via the `CamelFileName` header).

                **2. Added logging throughout:**
                - `@Slf4j` (Lombok) for a class-level logger, plus a startup log in `configure()`.
                - Camel `.log()` DSL statements at every meaningful step — file picked up → uploaded → download requested → downloaded → saved.
                - Gave each route an explicit `routeId` for easier monitoring/troubleshooting.

                **3. Minor cleanup:** extracted the account, container, and access key into constants so they're shared between the upload and download routes (no duplicated secret).

                ## How to trigger the download

                The new route is wired to `direct:downloadFromAzure`. Invoke it (e.g., from another route or a producer) with the blob name in the `CamelFileName` header:

                ```java
                producerTemplate.sendBodyAndHeader("direct:downloadFromAzure", null, Exchange.FILE_NAME, "myfile.txt");
                ```

                If you'd rather it run automatically on a schedule, I can swap the `direct:` trigger for a `timer` or `quartz` endpoint — just say the word.
                """;

        // 1. Verify parser segments
        List<MarkdownTableParser.Segment> segments = MarkdownTableParser.parseSegments(userSampleMessage);
        assertEquals(5, segments.size(), "Should have 5 segments: text, code, text, code, text");

        assertInstanceOf(MarkdownTableParser.TextSegment.class, segments.get(0));
        assertTrue(((MarkdownTableParser.TextSegment) segments.get(0)).text().contains("## What I changed"));
        assertTrue(((MarkdownTableParser.TextSegment) segments.get(0)).text().contains("**1. Added"));

        assertInstanceOf(MarkdownTableParser.CodeBlockSegment.class, segments.get(1));
        assertEquals("java", ((MarkdownTableParser.CodeBlockSegment) segments.get(1)).language());
        assertTrue(((MarkdownTableParser.CodeBlockSegment) segments.get(1)).code().contains("direct:downloadFromAzure"));

        assertInstanceOf(MarkdownTableParser.TextSegment.class, segments.get(2));
        assertTrue(((MarkdownTableParser.TextSegment) segments.get(2)).text().contains("**2. Added logging"));
        assertTrue(((MarkdownTableParser.TextSegment) segments.get(2)).text().contains("## How to trigger"));

        assertInstanceOf(MarkdownTableParser.CodeBlockSegment.class, segments.get(3));
        assertEquals("java", ((MarkdownTableParser.CodeBlockSegment) segments.get(3)).language());
        assertTrue(((MarkdownTableParser.CodeBlockSegment) segments.get(3)).code().contains("producerTemplate.sendBodyAndHeader"));

        assertInstanceOf(MarkdownTableParser.TextSegment.class, segments.get(4));

        // 2. Verify rendering inside AIAgentPanel
        AIAgentPanel[] holder = new AIAgentPanel[1];
        SwingUtilities.invokeAndWait(() -> holder[0] = new AIAgentPanel(() -> "", new TerminalPanel()));
        AIAgentPanel panel = holder[0];
        flushEdt();

        AgentSession session = SessionManager.getInstance().createSession("Formatting Session", "MOCK", "mock-agent", false);
        session.addMessage(new AgentMessage(com.github.axiomate.agentic.ide.agent.AgentRole.USER, "Download route", null));
        session.addMessage(new AgentMessage(com.github.axiomate.agentic.ide.agent.AgentRole.ASSISTANT, userSampleMessage, null));

        SwingUtilities.invokeAndWait(panel::reloadChatFromSession);
        flushEdt();

        // Check that MarkdownCodePanel instances exist
        List<MarkdownCodePanel> codePanels = findAll(panel, MarkdownCodePanel.class);
        assertEquals(2, codePanels.size(), "Should contain 2 MarkdownCodePanel blocks for ```java");

        // Check that MarkdownTextPanel instances exist
        List<MarkdownTextPanel> textPanels = findAll(panel, MarkdownTextPanel.class);
        assertEquals(3, textPanels.size(), "Should contain 3 MarkdownTextPanel blocks with rich typography");

        // Verify HTML contents inside textPanels
        boolean foundH2 = textPanels.stream().anyMatch(p -> p.getEditorPane().getText().contains("What I changed"));
        boolean foundBold = textPanels.stream().anyMatch(p -> p.getEditorPane().getText().contains("1. Added a new download route"));
        boolean foundSecondH2 = textPanels.stream().anyMatch(p -> p.getEditorPane().getText().contains("How to trigger the download"));
        assertTrue(foundH2, "EditorPane should render H2 heading");
        assertTrue(foundBold, "EditorPane should render bold text");
        assertTrue(foundSecondH2, "EditorPane should render second H2 heading");
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
