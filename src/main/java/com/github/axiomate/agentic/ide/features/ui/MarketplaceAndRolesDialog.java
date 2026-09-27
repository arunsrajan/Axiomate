package com.github.axiomate.agentic.ide.features.ui;

import com.github.axiomate.agentic.ide.features.extensibility.*;
import com.github.axiomate.agentic.ide.ui.util.UIUtils;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.util.List;

/**
 * Visual dialog for Custom Agent Roles (Feature 42), Workflow Recorder (Feature 43),
 * Tool Connector Marketplace (Feature 44), and Policy-as-Code (Feature 45).
 */
public class MarketplaceAndRolesDialog extends JDialog {

    public MarketplaceAndRolesDialog(Frame owner) {
        super(owner, "Axiomate - Agent Roles, Marketplace & Governance", false);
        setSize(950, 620);
        setLocationRelativeTo(owner);

        JTabbedPane tabs = new JTabbedPane();
        tabs.setFont(new Font("SansSerif", Font.PLAIN, 12));

        // 1. Custom Agent Roles Tab (Feature 42)
        JPanel rolesTab = new JPanel(new BorderLayout(8, 8));
        rolesTab.setBorder(new EmptyBorder(10, 10, 10, 10));
        String[] roleCols = {"Role Name", "Specialization", "Risk Profile", "Allowed Tools"};
        DefaultTableModel roleModel = new DefaultTableModel(roleCols, 0);
        for (AgentRoleDefinition r : CustomAgentRolesRegistry.getInstance().getAllRoles()) {
            roleModel.addRow(new Object[]{
                    r.roleName(), String.join(", ", r.specializations()), r.riskTolerance(), String.join(", ", r.allowedTools())
            });
        }
        JTable roleTable = new JTable(roleModel);
        roleTable.setFont(new Font("SansSerif", Font.PLAIN, 12));
        rolesTab.add(new JScrollPane(roleTable), BorderLayout.CENTER);

        // 2. Connector Marketplace Tab (Feature 44)
        JPanel marketTab = new JPanel(new BorderLayout(8, 8));
        marketTab.setBorder(new EmptyBorder(10, 10, 10, 10));
        String[] marketCols = {"Connector", "Category", "Provider", "Protocol", "Status", "Exposed Tools"};
        DefaultTableModel marketModel = new DefaultTableModel(marketCols, 0);
        for (MarketplaceConnector c : ToolConnectorMarketplace.getInstance().getAllConnectors()) {
            marketModel.addRow(new Object[]{
                    c.name(), c.category(), c.providerName(), c.protocolType(),
                    c.installed() ? (c.enabled() ? "🟢 Enabled" : "⚪ Disabled") : "📥 Available",
                    String.join(", ", c.exposedTools())
            });
        }
        JTable marketTable = new JTable(marketModel);
        marketTable.setFont(new Font("SansSerif", Font.PLAIN, 12));
        marketTab.add(new JScrollPane(marketTable), BorderLayout.CENTER);

        // 3. Policy-as-Code Tab (Feature 45)
        JPanel policyTab = new JPanel(new BorderLayout(8, 8));
        policyTab.setBorder(new EmptyBorder(10, 10, 10, 10));
        String[] polCols = {"Policy Rule", "Path Glob", "Forbidden Action", "Enforcement", "Rationale"};
        DefaultTableModel polModel = new DefaultTableModel(polCols, 0);
        for (AgentPolicyRule p : PolicyAsCodeEngine.getInstance().getPolicies()) {
            polModel.addRow(new Object[]{
                    p.name(), p.pathGlob(), p.forbiddenAction(), p.enforcementLevel(), p.rationale()
            });
        }
        JTable polTable = new JTable(polModel);
        polTable.setFont(new Font("SansSerif", Font.PLAIN, 12));
        policyTab.add(new JScrollPane(polTable), BorderLayout.CENTER);

        // 4. Workflow Skills Tab (Feature 43)
        JPanel skillsTab = new JPanel(new BorderLayout(8, 8));
        skillsTab.setBorder(new EmptyBorder(10, 10, 10, 10));
        String[] skillCols = {"Skill Name", "Description", "Steps Count"};
        DefaultTableModel skillModel = new DefaultTableModel(skillCols, 0);
        for (AgentSkill s : WorkflowRecorderSkillService.getInstance().getSavedSkills()) {
            skillModel.addRow(new Object[]{s.skillName(), s.description(), s.steps().size()});
        }
        JTable skillTable = new JTable(skillModel);
        skillTable.setFont(new Font("SansSerif", Font.PLAIN, 12));
        skillsTab.add(new JScrollPane(skillTable), BorderLayout.CENTER);

        tabs.addTab("👤 Specialized Agent Roles", rolesTab);
        tabs.addTab("🔌 Tool Marketplace", marketTab);
        tabs.addTab("📜 Policy-as-Code", policyTab);
        tabs.addTab("⚡ Workflow Skills", skillsTab);

        add(tabs, BorderLayout.CENTER);
    }
}
