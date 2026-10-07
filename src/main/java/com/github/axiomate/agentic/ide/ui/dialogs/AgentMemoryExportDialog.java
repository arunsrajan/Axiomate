package com.github.axiomate.agentic.ide.ui.dialogs;

import com.github.axiomate.agentic.ide.agent.memory.MemoryItem;
import com.github.axiomate.agentic.ide.agent.memory.MemoryManager;
import com.github.axiomate.agentic.ide.agent.memory.MemoryType;
import com.github.axiomate.agentic.ide.interop.AgentMemoryInterop;
import com.github.axiomate.agentic.ide.interop.AgentMemoryInterop.ExportPlan;
import com.github.axiomate.agentic.ide.interop.CodingAgent;
import com.github.axiomate.agentic.ide.interop.CodingAgentCatalog;
import com.github.axiomate.agentic.ide.interop.MemoryScope;
import com.github.axiomate.agentic.ide.ui.util.Toast;
import com.github.axiomate.agentic.ide.ui.util.UIUtils;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.nio.file.Path;
import java.util.*;
import java.util.List;

/**
 * Exports Axiomate memories into other coding agents' native instruction files, with a per-file preview.
 * Shared files (CLAUDE.md, AGENTS.md, GEMINI.md...) only get a replaceable Axiomate block.
 */
public class AgentMemoryExportDialog extends JDialog {

    record Row(CodingAgent agent, ExportPlan plan, String error) {
        boolean supported() {
            return plan != null;
        }
    }

    private static final Set<CodingAgent> DEFAULT_AGENTS = EnumSet.of(CodingAgent.CLAUDE_CODE, CodingAgent.CODEX,
            CodingAgent.CURSOR, CodingAgent.ANTIGRAVITY);

    private final Path projectDir;
    private final AgentMemoryInterop interop = new AgentMemoryInterop();
    private final CheckTableModel<Row> model;
    private final JTable table;
    private final JTextArea preview = DialogSupport.previewArea();
    private final JRadioButton projectScope = new JRadioButton("Project", true);
    private final JRadioButton userScope = new JRadioButton("User (global)");
    private final Map<MemoryType, JCheckBox> typeChecks = new EnumMap<>(MemoryType.class);
    private final JLabel summary = new JLabel(" ");
    private final JButton exportBtn = DialogSupport.primary("Export");

    public AgentMemoryExportDialog(Window owner, Path projectDir) {
        super(owner, "Export Memory to Coding Agents", ModalityType.APPLICATION_MODAL);
        this.projectDir = projectDir;
        setSize(1000, 660);
        setLocationRelativeTo(owner);
        setLayout(new BorderLayout());
        add(DialogSupport.banner("Export Axiomate memory to other coding agents",
                "Writes your project rules and knowledge where each agent reads them. Shared files such as CLAUDE.md or "
                        + "AGENTS.md keep your own content — only a marked Axiomate section is added or replaced."), BorderLayout.NORTH);

        model = new CheckTableModel<>(new String[]{"Agent", "Target file", "Action"}, (r, c) -> switch (c) {
            case 0 -> r.agent().getDisplayName();
            case 1 -> r.plan() != null ? display(r.plan().target()) : "—";
            default -> r.error() != null ? r.error() : describe(r.plan());
        });
        model.setCheckable(Row::supported);
        table = DialogSupport.table(model, 32, 140, 300, 200);
        table.getSelectionModel().addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) showPreview(model.getRow(table.getSelectedRow()));
        });
        model.addTableModelListener(e -> updateSummary());

        JPanel optionsBar = new JPanel(new com.github.axiomate.agentic.ide.ui.util.WrapLayout(FlowLayout.LEFT, 8, 2));
        optionsBar.add(new JLabel("Scope:"));
        ButtonGroup g = new ButtonGroup();
        g.add(projectScope);
        g.add(userScope);
        optionsBar.add(projectScope);
        optionsBar.add(userScope);
        projectScope.addActionListener(e -> replan());
        userScope.addActionListener(e -> replan());
        optionsBar.add(Box.createHorizontalStrut(16));
        optionsBar.add(new JLabel("Include:"));
        for (MemoryType t : List.of(MemoryType.PROJECT_RULE, MemoryType.LONG_TERM, MemoryType.EPISODIC, MemoryType.WORKING)) {
            JCheckBox cb = new JCheckBox(label(t), t == MemoryType.PROJECT_RULE || t == MemoryType.LONG_TERM);
            cb.addActionListener(e -> replan());
            typeChecks.put(t, cb);
            optionsBar.add(cb);
        }
        if (projectDir == null) {
            projectScope.setEnabled(false);
            userScope.setSelected(true);
        }

        JPanel left = new JPanel(new BorderLayout(0, 6));
        left.setBorder(new EmptyBorder(10, 12, 0, 6));
        left.add(optionsBar, BorderLayout.NORTH);
        left.add(new JScrollPane(table), BorderLayout.CENTER);

        JPanel right = new JPanel(new BorderLayout(0, 6));
        right.setBorder(new EmptyBorder(10, 6, 0, 12));
        right.add(UIUtils.sectionHeader("Resulting file"), BorderLayout.NORTH);
        preview.setLineWrap(false);
        right.add(new JScrollPane(preview), BorderLayout.CENTER);

        JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, left, right);
        split.setResizeWeight(0.5);
        split.setDividerLocation(540);
        split.setBorder(null);
        add(split, BorderLayout.CENTER);

        JButton close = new JButton("Close");
        close.addActionListener(e -> dispose());
        exportBtn.addActionListener(e -> doExport());
        add(DialogSupport.buttonBar(summary, close, exportBtn), BorderLayout.SOUTH);
        DialogSupport.closeOnEscape(this);
        replan();
    }

    private static String label(MemoryType t) {
        return switch (t) {
            case PROJECT_RULE -> "Project rules";
            case LONG_TERM -> "Long-term";
            case EPISODIC -> "Episodic";
            case WORKING -> "Working";
        };
    }

    private MemoryScope scope() {
        return userScope.isSelected() ? MemoryScope.USER : MemoryScope.PROJECT;
    }

    private void replan() {
        MemoryScope scope = scope();
        Set<MemoryType> types = EnumSet.noneOf(MemoryType.class);
        typeChecks.forEach((t, cb) -> {
            if (cb.isSelected()) types.add(t);
        });
        List<MemoryItem> items = AgentMemoryInterop.selectForExport(
                MemoryManager.getInstance().getMemoryStore().getAllMemories(), projectDir, scope, types);
        List<Row> rows = new ArrayList<>();
        for (CodingAgent a : CodingAgent.values()) {
            if (CodingAgentCatalog.exportTarget(a, scope).isEmpty()) {
                rows.add(new Row(a, null, "No file-based " + scope.getLabel().toLowerCase() + " rules"));
                continue;
            }
            try {
                rows.add(new Row(a, interop.planExport(a, scope, projectDir, items).orElse(null), null));
            } catch (Exception ex) {
                rows.add(new Row(a, null, "⚠ " + ex.getMessage()));
            }
        }
        model.setRows(rows, r -> DEFAULT_AGENTS.contains(r.agent()));
        if (model.getRowCount() > 0) table.setRowSelectionInterval(0, 0);
        updateSummary();
    }

    private String display(Path p) {
        if (projectDir != null && p.startsWith(projectDir)) return projectDir.relativize(p).toString().replace('\\', '/');
        Path home = interop.getHomeDir();
        if (p.startsWith(home)) return "~/" + home.relativize(p).toString().replace('\\', '/');
        return p.toString();
    }

    private static String describe(ExportPlan plan) {
        String n = plan.itemCount() + " memories";
        if (plan.style() == CodingAgentCatalog.ExportStyle.MANAGED_BLOCK) {
            return (plan.targetExists() ? "Update block · " : "New file · ") + n;
        }
        return (plan.targetExists() ? "Replace rule file · " : "New rule file · ") + n;
    }

    private void showPreview(Row row) {
        if (row == null || row.plan() == null) {
            preview.setText(row != null && row.error() != null ? row.error() : "");
            return;
        }
        preview.setText(row.plan().newContent());
        preview.setCaretPosition(0);
    }

    private void updateSummary() {
        List<Row> checked = model.getChecked();
        int items = checked.stream().mapToInt(r -> r.plan().itemCount()).max().orElse(0);
        summary.setText(checked.size() + " agent(s) selected · up to " + items + " memories per file");
        exportBtn.setEnabled(!checked.isEmpty());
    }

    private void doExport() {
        List<Row> checked = model.getChecked();
        StringBuilder files = new StringBuilder();
        for (Row r : checked) files.append("• ").append(display(r.plan().target())).append('\n');
        int ok = JOptionPane.showConfirmDialog(this, "Write these files?\n\n" + files, "Confirm Export",
                JOptionPane.OK_CANCEL_OPTION, JOptionPane.QUESTION_MESSAGE);
        if (ok != JOptionPane.OK_OPTION) return;
        List<String> failures = new ArrayList<>();
        int written = 0;
        for (Row r : checked) {
            try {
                interop.apply(r.plan());
                written++;
            } catch (Exception ex) {
                failures.add(r.agent().getDisplayName() + ": " + ex.getMessage());
            }
        }
        if (failures.isEmpty()) {
            Toast.success(getOwner(), "Exported memory to " + written + " agent file(s).");
            dispose();
        } else {
            Toast.error(this, "Some exports failed:\n" + String.join("\n", failures));
            replan();
        }
    }
}
