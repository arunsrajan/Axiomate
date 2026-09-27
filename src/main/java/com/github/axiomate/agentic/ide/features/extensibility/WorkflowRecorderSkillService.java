package com.github.axiomate.agentic.ide.features.extensibility;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Feature 43: Workflow recorder to skill.
 * The agent watches you do a multi-step procedure once and turns it into a reusable skill.
 */
public class WorkflowRecorderSkillService {

    private static final Logger log = LoggerFactory.getLogger(WorkflowRecorderSkillService.class);
    private static WorkflowRecorderSkillService instance;

    private boolean recording = false;
    private final List<RecordedAction> currentSessionActions = new CopyOnWriteArrayList<>();
    private final Map<String, AgentSkill> savedSkills = new LinkedHashMap<>();

    private WorkflowRecorderSkillService() {
        initDefaultSkills();
    }

    public static synchronized WorkflowRecorderSkillService getInstance() {
        if (instance == null) {
            instance = new WorkflowRecorderSkillService();
        }
        return instance;
    }

    private void initDefaultSkills() {
        savedSkills.put("skill-release-prep", new AgentSkill(
                "skill-release-prep",
                "Release Preparation & Sanity Check",
                "Runs compilation, executes full test suite, runs dependency audit, and tags release.",
                List.of("versionTag"),
                List.of(
                        new AgentSkill.SkillStep(1, "terminal", "Clean compile codebase", "{\"command\": \"mvn clean compile\"}"),
                        new AgentSkill.SkillStep(2, "terminal", "Run automated tests", "{\"command\": \"mvn test\"}"),
                        new AgentSkill.SkillStep(3, "secret_leak_guard", "Verify zero credential leaks", "{\"action\": \"scan_all\"}")
                ),
                "# Release Prep Skill\nAutomated workflow for release readiness."
        ));
    }

    public boolean isRecording() { return recording; }

    public void startRecording() {
        this.recording = true;
        this.currentSessionActions.clear();
        log.info("Started workflow recording session");
    }

    public void recordAction(String actionType, String subject, Map<String, String> params) {
        if (recording) {
            RecordedAction act = new RecordedAction(
                    currentSessionActions.size() + 1,
                    actionType,
                    subject,
                    params != null ? new HashMap<>(params) : Map.of(),
                    Instant.now()
            );
            currentSessionActions.add(act);
            log.info("Recorded action #{}: {} on {}", act.sequenceIndex(), actionType, subject);
        }
    }

    public AgentSkill stopRecordingAndSynthesize(String skillName, String description) {
        this.recording = false;
        log.info("Stopped recording. Synthesizing skill '{}' from {} actions", skillName, currentSessionActions.size());

        String skillId = "skill-" + skillName.toLowerCase().replaceAll("[^a-z0-9]+", "-");
        List<AgentSkill.SkillStep> steps = new ArrayList<>();

        for (RecordedAction a : currentSessionActions) {
            String tool = a.actionType().equalsIgnoreCase("RUN_COMMAND") ? "terminal" : "code_editor";
            steps.add(new AgentSkill.SkillStep(
                    a.sequenceIndex(),
                    tool,
                    a.actionType() + " on " + a.targetSubject(),
                    "{\"subject\": \"" + a.targetSubject() + "\"}"
            ));
        }

        if (steps.isEmpty()) {
            steps.add(new AgentSkill.SkillStep(1, "code_editor", "Sample recorded step", "{}"));
        }

        String markdown = String.format("""
                # Skill: %s
                %s
                
                ## Steps:
                %s
                """, skillName, description, steps.stream().map(s -> s.stepNumber() + ". " + s.description()).reduce("", (a, b) -> a + "\n" + b));

        AgentSkill skill = new AgentSkill(skillId, skillName, description, List.of("targetProjectDir"), steps, markdown);
        savedSkills.put(skillId, skill);
        return skill;
    }

    public List<AgentSkill> getSavedSkills() {
        return new ArrayList<>(savedSkills.values());
    }

    public List<RecordedAction> getCurrentSessionActions() {
        return new ArrayList<>(currentSessionActions);
    }
}
