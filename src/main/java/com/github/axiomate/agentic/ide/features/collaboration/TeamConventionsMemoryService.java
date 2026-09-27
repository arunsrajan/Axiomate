package com.github.axiomate.agentic.ide.features.collaboration;

import com.github.axiomate.agentic.ide.agent.memory.MemoryItem;
import com.github.axiomate.agentic.ide.agent.memory.MemoryManager;
import com.github.axiomate.agentic.ide.agent.memory.MemoryType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Feature 40: Team conventions memory.
 * The agent learns style, naming, and patterns from accepted PRs and stops repeating rejected ones.
 */
public class TeamConventionsMemoryService {

    private static final Logger log = LoggerFactory.getLogger(TeamConventionsMemoryService.class);
    private static TeamConventionsMemoryService instance;

    private final List<TeamConvention> conventions = new CopyOnWriteArrayList<>();

    private TeamConventionsMemoryService() {
        initDefaultConventions();
    }

    public static synchronized TeamConventionsMemoryService getInstance() {
        if (instance == null) {
            instance = new TeamConventionsMemoryService();
        }
        return instance;
    }

    private void initDefaultConventions() {
        learnConvention(new TeamConvention(
                "conv-naming-records",
                "NAMING",
                "Prefer Java 21 records for immutable DTOs and data carriers instead of Lombok @Data",
                "public record UserProfile(String id, String email) {}",
                "@Data public class UserProfile { private String id; ... }",
                98,
                Instant.now(),
                "PR #12 (Accepted)"
        ));

        learnConvention(new TeamConvention(
                "conv-anti-raw-types",
                "ANTI_PATTERN",
                "Never use raw generic types like List or Map; always specify typed type-parameters",
                "List<String> items = new ArrayList<>();",
                "List items = new ArrayList();",
                99,
                Instant.now(),
                "PR #18 (Rejected review feedback)"
        ));

        learnConvention(new TeamConvention(
                "conv-logging-slf4j",
                "STYLE",
                "Always use private static final Logger log = LoggerFactory.getLogger(Class.class) with parameterized placeholders",
                "log.info(\"Processed item: {}\", item);",
                "System.out.println(\"Processed item: \" + item);",
                96,
                Instant.now(),
                "PR #22 (Accepted)"
        ));
    }

    public List<TeamConvention> getConventions() {
        return new ArrayList<>(conventions);
    }

    public void learnConvention(TeamConvention convention) {
        if (convention != null) {
            conventions.removeIf(c -> c.conventionId().equalsIgnoreCase(convention.conventionId()));
            conventions.add(convention);
            log.info("Learned team convention: {}", convention.ruleDescription());

            // Persist into Agentic Memory PROJECT_RULE tier
            try {
                MemoryItem memoryItem = new MemoryItem(
                        MemoryType.PROJECT_RULE,
                        convention.ruleDescription(),
                        convention.ruleDescription() + "\nPositive: " + convention.positiveExample() + "\nNegative: " + convention.negativeExample(),
                        List.of("convention", convention.category().toLowerCase())
                );
                memoryItem.setId(convention.conventionId());
                memoryItem.setImportance(convention.confidenceScore() / 100.0);
                MemoryManager.getInstance().getMemoryStore().addMemory(memoryItem);
                MemoryManager.getInstance().getMemoryStore().save();
            } catch (Exception e) {
                log.warn("Could not sync team convention to MemoryStore", e);
            }
        }
    }
}
