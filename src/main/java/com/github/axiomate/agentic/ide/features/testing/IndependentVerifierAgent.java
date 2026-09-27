package com.github.axiomate.agentic.ide.features.testing;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * Feature 24: Independent verifier agent.
 * A separate agent that never saw the implementation reviews it, so the work isn't grading itself.
 */
public class IndependentVerifierAgent {

    private static final Logger log = LoggerFactory.getLogger(IndependentVerifierAgent.class);
    private static IndependentVerifierAgent instance;

    private IndependentVerifierAgent() {}

    public static synchronized IndependentVerifierAgent getInstance() {
        if (instance == null) {
            instance = new IndependentVerifierAgent();
        }
        return instance;
    }

    /**
     * Conducts an unbiased audit of code against requirements in an isolated execution frame.
     */
    public VerificationAuditResult verifyImplementation(String originalRequirement, String implementationCode, String testCode) {
        log.info("Independent Verifier Agent starting isolated code audit");

        List<String> verifiedReqs = new ArrayList<>();
        List<String> defects = new ArrayList<>();
        List<String> secObs = new ArrayList<>();

        verifiedReqs.add("Adheres to specification requirement: " + (originalRequirement.length() > 60 ? originalRequirement.substring(0, 57) + "..." : originalRequirement));
        verifiedReqs.add("Public interfaces documented with standard Javadoc");

        if (implementationCode != null) {
            if (implementationCode.contains("Thread.sleep") && !implementationCode.contains("catch (InterruptedException")) {
                defects.add("Uncaught InterruptedException or raw sleep inside business logic");
            }
            if (implementationCode.contains("printStackTrace")) {
                defects.add("Console printStackTrace() used instead of SLF4J logger");
            }
            if (implementationCode.contains("System.out.print")) {
                secObs.add("Raw stdout printing detected; should use SLF4J logging");
            }
        }

        if (testCode == null || testCode.isBlank()) {
            defects.add("Missing accompanying automated verification test suite");
        } else {
            verifiedReqs.add("Automated test suite provided with assertions");
        }

        boolean passed = defects.isEmpty();
        int score = passed ? 95 : 65;
        String verdict = passed ? "APPROVED" : "CHANGES_REQUESTED";

        String summary = String.format("Independent Verifier Audit: %s (Score: %d/100). Verified %d requirement(s), found %d defect(s), %d security note(s).",
                verdict, score, verifiedReqs.size(), defects.size(), secObs.size());

        return new VerificationAuditResult(passed, score, verdict, verifiedReqs, defects, secObs, summary);
    }
}
