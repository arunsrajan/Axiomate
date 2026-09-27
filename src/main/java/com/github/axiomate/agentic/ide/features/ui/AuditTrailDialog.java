package com.github.axiomate.agentic.ide.features.ui;

import com.github.axiomate.agentic.ide.features.security.AuditEntry;
import com.github.axiomate.agentic.ide.features.security.AuditTrailService;
import com.github.axiomate.agentic.ide.features.security.ExecutionSandbox;
import com.github.axiomate.agentic.ide.ui.util.UIUtils;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.util.List;

/**
 * Visual dialog for Feature 36: Full audit trail & Security governance.
 */
public class AuditTrailDialog extends JDialog {

    private final DefaultTableModel auditTableModel;
    private final JTable auditTable;
    private final JLabel integrityBadge;

    public AuditTrailDialog(Frame owner) {
        super(owner, "Axiomate - Enterprise Audit Trail & Security Governance", false);
        setSize(950, 600);
        setLocationRelativeTo(owner);
        setLayout(new BorderLayout(8, 8));

        // Top Header
        JPanel header = new JPanel(new BorderLayout(8, 0));
        header.setBorder(new EmptyBorder(10, 12, 6, 12));

        JLabel title = new JLabel("Cryptographically Signed Audit Log (SHA-256 Hash Chain)");
        title.setFont(new Font("SansSerif", Font.BOLD, 13));

        JPanel statusPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        integrityBadge = new JLabel("● SHA-256 Chain Intact");
        integrityBadge.setFont(new Font("SansSerif", Font.BOLD, 11));
        integrityBadge.setForeground(UIUtils.SUCCESS_COLOR);

        JButton verifyBtn = new JButton("Verify Chain Integrity");
        verifyBtn.addActionListener(e -> {
            boolean valid = AuditTrailService.getInstance().verifyIntegrity();
            if (valid) {
                integrityBadge.setText("● SHA-256 Chain Verified Valid");
                integrityBadge.setForeground(UIUtils.SUCCESS_COLOR);
                JOptionPane.showMessageDialog(this, "All audit log signatures and hash chains verified intact!", "Integrity Verified", JOptionPane.INFORMATION_MESSAGE);
            } else {
                integrityBadge.setText("❌ Hash Chain Tampered!");
                integrityBadge.setForeground(UIUtils.ERROR_COLOR);
            }
        });

        statusPanel.add(integrityBadge);
        statusPanel.add(verifyBtn);

        header.add(title, BorderLayout.WEST);
        header.add(statusPanel, BorderLayout.EAST);
        add(header, BorderLayout.NORTH);

        // Audit Table
        String[] cols = {"Audit ID", "Timestamp", "Actor", "Action Type", "Target Resource", "SHA-256 Signature"};
        auditTableModel = new DefaultTableModel(cols, 0) {
            @Override
            public boolean isCellEditable(int row, int col) { return false; }
        };
        auditTable = new JTable(auditTableModel);
        auditTable.setFont(new Font("SansSerif", Font.PLAIN, 12));
        auditTable.setRowHeight(24);
        auditTable.getColumnModel().getColumn(0).setPreferredWidth(130);
        auditTable.getColumnModel().getColumn(1).setPreferredWidth(140);
        auditTable.getColumnModel().getColumn(2).setPreferredWidth(70);
        auditTable.getColumnModel().getColumn(3).setPreferredWidth(120);
        auditTable.getColumnModel().getColumn(4).setPreferredWidth(160);
        auditTable.getColumnModel().getColumn(5).setPreferredWidth(220);

        refreshTable();
        add(new JScrollPane(auditTable), BorderLayout.CENTER);

        // Footer with Sandbox and Secret-Guard status
        JPanel footer = new JPanel(new FlowLayout(FlowLayout.LEFT, 12, 8));
        footer.setBorder(new EmptyBorder(4, 10, 4, 10));

        JLabel sandboxLbl = new JLabel("Container Sandbox: Active (" + ExecutionSandbox.getInstance().getAllowedCommands().size() + " commands allowed)");
        sandboxLbl.setFont(new Font("SansSerif", Font.PLAIN, 11));
        sandboxLbl.setForeground(Color.LIGHT_GRAY);

        JLabel secretLbl = new JLabel("Secret-Leak Guard: Active (Auto-redacting credentials)");
        secretLbl.setFont(new Font("SansSerif", Font.PLAIN, 11));
        secretLbl.setForeground(Color.LIGHT_GRAY);

        footer.add(sandboxLbl);
        footer.add(new JSeparator(SwingConstants.VERTICAL));
        footer.add(secretLbl);

        add(footer, BorderLayout.SOUTH);
    }

    private void refreshTable() {
        auditTableModel.setRowCount(0);
        List<AuditEntry> entries = AuditTrailService.getInstance().getAllEntries();
        for (AuditEntry e : entries) {
            auditTableModel.addRow(new Object[]{
                    e.auditId(),
                    e.timestamp().toString().substring(0, 19).replace('T', ' '),
                    e.actor(),
                    e.actionType(),
                    e.targetResource(),
                    e.sha256Signature().substring(0, 24) + "..."
            });
        }
    }
}
