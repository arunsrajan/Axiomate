package com.github.axiomate.agentic.ide.ui.dialogs;

import com.github.axiomate.agentic.ide.agent.memory.MemoryItem;
import com.github.axiomate.agentic.ide.agent.memory.MemoryManager;
import com.github.axiomate.agentic.ide.interop.AgentMemoryInterop;
import com.github.axiomate.agentic.ide.interop.AgentMemoryInterop.DetectedSource;
import com.github.axiomate.agentic.ide.interop.AgentMemoryInterop.ImportOptions;
import com.github.axiomate.agentic.ide.interop.AgentMemoryInterop.ImportResult;
import com.github.axiomate.agentic.ide.interop.CodingAgent;
import com.github.axiomate.agentic.ide.ui.util.Toast;
import com.github.axiomate.agentic.ide.ui.util.UIUtils;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Detects memory/rule files from other coding agents, previews the memories they contain, and imports them.
 */
public class AgentMemoryImportDialog extends JDialog {

    record Row(DetectedSource source, List<MemoryItem> items, String error) {
    }

    private final Path projectDir;
    private final AgentMemoryInterop interop = new AgentMemoryInterop();
    private final CheckTableModel<Row> model;
    private final JTable table;
    private final JTextArea preview = DialogSupport.previewArea();
    private final JComboBox<Object> agentFilter = new JComboBox<>();
    private final JCheckBox replaceCheck = new JCheckBox("Replace memories previously imported from the same files", true);
    private final JCheckBox scopeCheck = new JCheckBox("Apply project rules only to this project", true);
    private final JCheckBox managedCheck = new JCheckBox("Include content Axiomate exported earlier", false);
    private final JLabel summary = new JLabel(" ");
    private final JButton importBtn = DialogSupport.primary("Import Selected");

    public AgentMemoryImportDialog(Window owner, Path projectDir) {
        super(owner, "Import Memory from Coding Agents", ModalityType.APPLICATION_MODAL);
        this.projectDir = projectDir;
        setSize(1000, 660);
        setLocationRelativeTo(owner);
        setLayout(new BorderLayout());
        add(DialogSupport.banner("Import memory from other coding agents",
                "Rules and memories from Claude Code (CLAUDE.md, auto memory), Codex (AGENTS.md), Cursor (.cursor/rules), "
                        + "Antigravity (.agents/rules, GEMINI.md), Gemini CLI, Windsurf, Copilot, Cline, Roo Code and Kiro. "
                        + "Re-importing a file updates its memories instead of duplicating them."), BorderLayout.NORTH);

        model = new CheckTableModel<>(new String[]{"Agent", "Scope", "File", "Memories"}, (r, c) -> switch (c) {
            case 0 -> r.source().agent().getDisplayName();
            case 1 -> r.source().location().scope().getLabel();
            case 2 -> r.source().displayPath(projectDir, interop.getHomeDir());
            default -> r.error() != null ? "⚠ " + r.error() : r.items().size();
        });
        model.setCheckable(r -> r.error() == null && !r.items().isEmpty());
        table = DialogSupport.table(model, 32, 165, 95, 300, 80);
        table.getSelectionModel().addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) showPreview(model.getRow(table.getSelectedRow()));
        });
        model.addTableModelListener(e -> updateSummary());

        JPanel left = new JPanel(new BorderLayout(0, 6));
        left.setBorder(new EmptyBorder(10, 12, 0, 6));
        JPanel filterBar = new JPanel(new com.github.axiomate.agentic.ide.ui.util.WrapLayout(FlowLayout.LEFT, 6, 2));
        filterBar.add(new JLabel("Agent:"));
        agentFilter.addItem("All agents");
        for (CodingAgent a : CodingAgent.values()) agentFilter.addItem(a);
        agentFilter.addActionListener(e -> rescan());
        filterBar.add(agentFilter);
        JButton all = new JButton("Select all");
        all.addActionListener(e -> model.setAllChecked(true));
        JButton none = new JButton("None");
        none.addActionListener(e -> model.setAllChecked(false));
        filterBar.add(all);
        filterBar.add(none);
        left.add(filterBar, BorderLayout.NORTH);
        left.add(new JScrollPane(table), BorderLayout.CENTER);

        JPanel options = new JPanel(new GridLayout(3, 1));
        options.add(replaceCheck);
        options.add(scopeCheck);
        options.add(managedCheck);
        managedCheck.addActionListener(e -> rescan());
        left.add(options, BorderLayout.SOUTH);

        JPanel right = new JPanel(new BorderLayout(0, 6));
        right.setBorder(new EmptyBorder(10, 6, 0, 12));
        right.add(UIUtils.sectionHeader("Preview"), BorderLayout.NORTH);
        right.add(new JScrollPane(preview), BorderLayout.CENTER);

        JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, left, right);
        split.setResizeWeight(0.62);
        split.setDividerLocation(600);
        split.setBorder(null);
        add(split, BorderLayout.CENTER);

        JButton rescanBtn = new JButton("Rescan");
        rescanBtn.addActionListener(e -> rescan());
        JButton close = new JButton("Close");
        close.addActionListener(e -> dispose());
        importBtn.addActionListener(e -> doImport());
        add(DialogSupport.buttonBar(summary, rescanBtn, close, importBtn), BorderLayout.SOUTH);
        DialogSupport.closeOnEscape(this);
        rescan();
    }

    private void rescan() {
        Object f = agentFilter.getSelectedItem();
        List<CodingAgent> agents = f instanceof CodingAgent a ? List.of(a) : List.copyOf(EnumSet.allOf(CodingAgent.class));
        ImportOptions opts = options();
        summary.setText("Scanning…");
        importBtn.setEnabled(false);
        new SwingWorker<List<Row>, Void>() {
            @Override
            protected List<Row> doInBackground() {
                List<Row> rows = new ArrayList<>();
                for (DetectedSource s : interop.detect(projectDir, agents)) {
                    try {
                        rows.add(new Row(s, interop.parse(s, projectDir, opts), null));
                    } catch (Exception ex) {
                        rows.add(new Row(s, List.of(), ex.getMessage()));
                    }
                }
                return rows;
            }

            @Override
            protected void done() {
                try {
                    model.setRows(get(), r -> true);
                    if (model.getRowCount() > 0) table.setRowSelectionInterval(0, 0);
                    else preview.setText("No memory files were found for the selected agent(s).\n\nLooked in the project folder"
                            + (projectDir != null ? " (" + projectDir + ")" : "") + " and your home directory.");
                } catch (Exception ex) {
                    summary.setText("Scan failed: " + ex.getMessage());
                }
                updateSummary();
            }
        }.execute();
    }

    private ImportOptions options() {
        return new ImportOptions(replaceCheck.isSelected(), managedCheck.isSelected(), scopeCheck.isSelected());
    }

    private void updateSummary() {
        List<Row> checked = model.getChecked();
        int items = checked.stream().mapToInt(r -> r.items().size()).sum();
        summary.setText(model.getRowCount() + " file(s) found · " + checked.size() + " selected · " + items + " memories");
        importBtn.setEnabled(!checked.isEmpty());
    }

    private void showPreview(Row row) {
        if (row == null) {
            preview.setText("");
            return;
        }
        StringBuilder sb = new StringBuilder();
        sb.append(row.source().file()).append("\n");
        sb.append(row.source().location().description()).append(" · ").append(row.source().location().type()).append("\n\n");
        if (row.error() != null) sb.append("⚠ ").append(row.error()).append('\n');
        if (row.items().isEmpty() && row.error() == null) {
            sb.append("Nothing to import (empty file, or only content previously exported by Axiomate).");
        }
        for (MemoryItem m : row.items()) {
            sb.append("■ ").append(m.getTitle()).append("  [").append(m.getType()).append("]\n");
            String c = m.getContent();
            sb.append(c.length() > 600 ? c.substring(0, 600) + "…" : c).append("\n\n");
        }
        preview.setText(sb.toString());
        preview.setCaretPosition(0);
    }

    private void doImport() {
        List<DetectedSource> sources = model.getChecked().stream().map(Row::source).collect(Collectors.toList());
        ImportResult result = interop.importInto(MemoryManager.getInstance().getMemoryStore(), sources, projectDir, options());
        MemoryManager.getInstance().notifyChanged();
        String perAgent = result.perAgent().entrySet().stream()
                .map((Map.Entry<CodingAgent, Integer> e) -> e.getKey().getDisplayName() + " " + e.getValue())
                .collect(Collectors.joining(", "));
        String msg = "Imported " + result.imported() + " memories from " + result.files() + " file(s)"
                + (perAgent.isEmpty() ? "" : " (" + perAgent + ")")
                + (result.replaced() > 0 ? ". Replaced " + result.replaced() + " older copies." : ".");
        if (!result.warnings().isEmpty()) msg += "\n" + String.join("\n", result.warnings());
        Toast.success(getOwner(), msg);
        dispose();
    }
}
