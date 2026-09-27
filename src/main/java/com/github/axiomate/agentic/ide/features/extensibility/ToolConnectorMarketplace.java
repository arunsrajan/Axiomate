package com.github.axiomate.agentic.ide.features.extensibility;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Feature 44: Tool and connector marketplace.
 * Plug-in access to Jira, Figma, Datadog, cloud consoles, and internal APIs via a standard protocol.
 */
public class ToolConnectorMarketplace {

    private static final Logger log = LoggerFactory.getLogger(ToolConnectorMarketplace.class);
    private static ToolConnectorMarketplace instance;

    private final Map<String, MarketplaceConnector> connectors = new ConcurrentHashMap<>();

    private ToolConnectorMarketplace() {
        initDefaultMarketplace();
    }

    public static synchronized ToolConnectorMarketplace getInstance() {
        if (instance == null) {
            instance = new ToolConnectorMarketplace();
        }
        return instance;
    }

    private void initDefaultMarketplace() {
        registerConnector(new MarketplaceConnector(
                "conn-jira",
                "Jira Issue & Sprint Tracker",
                "ISSUE_TRACKING",
                "Atlassian",
                "Read, search, update issues, transition tickets, and link pull requests via Jira REST API / MCP.",
                "1.4.0",
                true,
                true,
                List.of("jira_get_issue", "jira_search_jql", "jira_transition_issue"),
                "MCP"
        ));

        registerConnector(new MarketplaceConnector(
                "conn-figma",
                "Figma Design System Inspector",
                "DESIGN",
                "Figma",
                "Extract vector assets, inspect UI frames, retrieve typography tokens, and generate UI code directly from design canvases.",
                "2.1.0",
                false,
                false,
                List.of("figma_get_file_nodes", "figma_export_component"),
                "REST_OPENAPI"
        ));

        registerConnector(new MarketplaceConnector(
                "conn-datadog",
                "Datadog APM & Log Telemetry",
                "MONITORING",
                "Datadog",
                "Query live distributed traces, monitor latency spikes, correlate errors with deploys, and fetch runtime metrics.",
                "1.2.5",
                true,
                true,
                List.of("datadog_query_traces", "datadog_get_active_alerts"),
                "MCP"
        ));

        registerConnector(new MarketplaceConnector(
                "conn-aws",
                "AWS Cloud Console & Lambdas",
                "CLOUD",
                "Amazon Web Services",
                "Inspect S3 buckets, query CloudWatch logs, invoke Lambda functions, and check ECS container health.",
                "3.0.1",
                false,
                false,
                List.of("aws_s3_get_object", "aws_cloudwatch_query_logs"),
                "MCP"
        ));

        registerConnector(new MarketplaceConnector(
                "conn-github",
                "GitHub Pull Requests & Actions",
                "INTEGRATION",
                "GitHub",
                "Manage PR reviews, trigger workflow dispatches, inspect CI logs, and post automated comments.",
                "2.0.0",
                true,
                true,
                List.of("github_create_pr", "github_get_workflow_logs"),
                "MCP"
        ));
    }

    public List<MarketplaceConnector> getAllConnectors() {
        return new ArrayList<>(connectors.values());
    }

    public Optional<MarketplaceConnector> getConnector(String id) {
        return Optional.ofNullable(connectors.get(id));
    }

    public void registerConnector(MarketplaceConnector connector) {
        if (connector != null) {
            connectors.put(connector.id(), connector);
        }
    }

    public void setConnectorInstalled(String id, boolean installed) {
        MarketplaceConnector conn = connectors.get(id);
        if (conn != null) {
            connectors.put(id, conn.withStatus(installed, installed ? conn.enabled() : false));
            log.info("Marketplace connector {} installed={}", id, installed);
        }
    }

    public void setConnectorEnabled(String id, boolean enabled) {
        MarketplaceConnector conn = connectors.get(id);
        if (conn != null) {
            connectors.put(id, conn.withStatus(conn.installed(), enabled));
            log.info("Marketplace connector {} enabled={}", id, enabled);
        }
    }
}
