package com.github.axiomate.agentic.ide.agent;

import com.github.axiomate.agentic.ide.agent.tools.FileSystemTool;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class AgentServiceTest {

    @Test
    @DisplayName("MockAgentService should handle explain request and stream response")
    void testMockAgentExplain() throws InterruptedException {
        MockAgentService service = new MockAgentService();
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<String> result = new AtomicReference<>("");
        AtomicBoolean thoughtReceived = new AtomicBoolean(false);

        service.sendMessage("Explain this code", "public class Sample {}", "Sample.java", new AgentListener() {
            @Override
            public void onToken(String token) {}

            @Override
            public void onThinking(String thought) {
                thoughtReceived.set(true);
            }

            @Override
            public void onToolCall(String toolName, String input) {}

            @Override
            public void onToolResult(String toolName, String output) {}

            @Override
            public void onComplete(String fullResponse) {
                result.set(fullResponse);
                latch.countDown();
            }

            @Override
            public void onError(Throwable throwable) {
                latch.countDown();
            }
        });

        boolean finished = latch.await(5, TimeUnit.SECONDS);
        assertTrue(finished, "Agent did not finish within timeout");
        assertTrue(thoughtReceived.get(), "Agent should have emitted thoughts");
        assertTrue(result.get().contains("Code Analysis"), "Response should contain analysis: " + result.get());
    }

    @Test
    @DisplayName("FileSystemTool should execute list action")
    void testFileSystemToolList() throws Exception {
        FileSystemTool tool = new FileSystemTool();
        String out = tool.execute("{\"action\": \"list\", \"path\": \"\"}");
        assertNotNull(out);
        assertTrue(out.contains("pom.xml") || out.contains("Directory contents"), "Should list directory files: " + out);
    }
}

