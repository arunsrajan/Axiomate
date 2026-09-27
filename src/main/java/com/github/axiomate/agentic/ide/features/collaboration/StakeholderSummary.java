package com.github.axiomate.agentic.ide.features.collaboration;

import java.util.List;

/**
 * Model representing tailored summaries for different project stakeholders.
 */
public record StakeholderSummary(
        String featureTitle,
        String executiveLeadershipBrief,
        String productManagerUpdate,
        String customerReleaseNotes,
        String supportTeamIncidentBrief,
        List<String> keyDeliverables
) {
    public String toMarkdown() {
        return String.format("""
                # 📢 Stakeholder Update: %s
                
                ### 👔 Executive Summary
                %s
                
                ### 🎯 Product Management (PM) Update
                %s
                
                ### 🚀 Customer-Facing Release Notes
                %s
                
                ### 🛠 Support & Operations Brief
                %s
                """, featureTitle, executiveLeadershipBrief, productManagerUpdate, customerReleaseNotes, supportTeamIncidentBrief);
    }
}
