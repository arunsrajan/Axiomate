package com.github.axiomate.agentic.ide.ui.dialogs;

import com.github.axiomate.agentic.ide.agent.session.AgentSession;
import com.github.axiomate.agentic.ide.agent.session.SessionManager;
import com.github.axiomate.agentic.ide.interop.ExternalSessionImporter;
import com.github.axiomate.agentic.ide.interop.ExternalSessionImporter.ExternalSessionRef;
import com.github.axiomate.agentic.ide.ui.util.Toast;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Lists Claude Code and Codex conversations recorded for the current project and imports them as Axiomate
 * sessions, so work can continue here with full history.
 */
public class ExternalSessionsDialog extends JDialog {

    private final Path projectDir;
    private final ExternalSessionImporter importer = new ExternalSessionImporter();
    private final CheckTableModel<ExternalSessionRef> model;
    private final JTable table;
    private final JCheckBox allProjects = new JCheckBox("Show Codex sessions from every project", false);
    private final JLabel status = new JLabel(" ");

    public ExternalSessionsDialog(Window owner, Path projectDir) {
        super(owner, "Import Sessions from Claude Code & Codex", ModalityType.APPLICATION_MODAL);
        this.projectDir = projectDir;
        setSize(900, 540);
        setLocationRelativeTo(owner);
        setLayout(new BorderLayout());
        add(DialogSupport.banner("Continue conversations from other agents",
                "Reads Claude Code transcripts (~/.claude/projects) and Codex rollouts (~/.codex/sessions) for this project. "
                        + "Prompts, replies, reasoning and tool calls are preserved. Re-importing updates the same session."), BorderLayout.NORTH);

        model = new CheckTableModel<>(new String[]{"Agent", "Conversation", "Started", "Size"}, (r, c) -> switch (c) {
            case 0 -> r.agent().getDisplayName();
            case 1 -> r.title();
            case 2 -> r.startedAt() != null ? r.startedAt() : "";
            default -> Math.max(1, r.sizeBytes() / 1024) + " KB";
        });
        table = DialogSupport.table(model, 32, 120, 480, 150, 80);

        JPanel center = new JPanel(new BorderLayout(0, 6));
        center.setBorder(new EmptyBorder(10, 12, 0, 12));
        center.add(new JScrollPane(table), BorderLayout.CENTER);
        allProjects.addActionListener(e -> reload());
        center.add(allProjects, BorderLayout.SOUTH);
        add(center, BorderLayout.CENTER);

        JButton close = new JButton("Close");
        close.addActionListener(e -> dispose());
        JButton importBtn = new JButton("Import");
        importBtn.addActionListener(e -> doImport(false));
        JButton importOpen = DialogSupport.primary("Import & Open");
        importOpen.addActionListener(e -> doImport(true));
        add(DialogSupport.buttonBar(status, close, importBtn, importOpen), BorderLayout.SOUTH);
        DialogSupport.closeOnEscape(this);
        reload();
    }

    private void reload() {
        status.setText("Scanning…");
        boolean all = allProjects.isSelected();
        new SwingWorker<List<ExternalSessionRef>, Void>() {
            @Override
            protected List<ExternalSessionRef> doInBackground() {
                List<ExternalSessionRef> refs = new ArrayList<>(importer.findAll(projectDir));
                if (all) {
                    for (ExternalSessionRef r : importer.findSessions(com.github.axiomate.agentic.ide.interop.CodingAgent.CODEX, null)) {
                        if (refs.stream().noneMatch(x -> x.file().equals(r.file()))) refs.add(r);
                    }
                }
                return refs;
            }

            @Override
            protected void done() {
                try {
                    List<ExternalSessionRef> refs = get();
                    model.setRows(refs, r -> false);
                    status.setText(refs.isEmpty()
                            ? "No Claude Code or Codex sessions found for this project."
                            : refs.size() + " conversation(s) found");
                } catch (Exception ex) {
                    status.setText("Scan failed: " + ex.getMessage());
                }
            }
        }.execute();
    }

    private void doImport(boolean open) {
        List<ExternalSessionRef> selected = model.getChecked();
        if (selected.isEmpty() && table.getSelectedRow() >= 0) {
            selected = List.of(model.getRow(table.getSelectedRow()));
        }
        if (selected.isEmpty()) {
            status.setText("Tick one or more conversations to import.");
            return;
        }
        int ok = 0;
        AgentSession last = null;
        List<String> errors = new ArrayList<>();
        for (ExternalSessionRef ref : selected) {
            try {
                last = importer.load(ref);
                SessionManager.getInstance().addImportedSession(last, false);
                ok++;
            } catch (Exception ex) {
                errors.add(ref.title() + ": " + ex.getMessage());
            }
        }
        if (open && last != null) SessionManager.getInstance().switchSession(last.getId());
        String msg = "Imported " + ok + " session(s)" + (errors.isEmpty() ? "" : "\nFailed: " + String.join("\n", errors));
        if (errors.isEmpty()) Toast.success(getOwner(), msg);
        else Toast.warning(getOwner(), msg);
        dispose();
    }
}
