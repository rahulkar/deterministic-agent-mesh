package com.agentmesh.deterministic.adk;

import com.google.adk.artifacts.InMemoryArtifactService;
import com.google.adk.events.Event;
import com.google.adk.memory.InMemoryMemoryService;
import com.google.adk.runner.Runner;
import com.google.adk.sessions.InMemorySessionService;
import com.google.genai.types.Content;
import com.google.genai.types.Part;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentMeshAdkAppTest {
    @AfterEach
    void tearDown() {
        AgentMeshDevUiAgent.shutdownRuntimeForTests();
    }

    @Test
    void exposesRootAgentForGoogleAdkDevUiDiscovery() {
        assertNotNull(AgentMeshAdkApp.ROOT_AGENT);
        assertEquals("deterministic-agent-mesh", AgentMeshAdkApp.ROOT_AGENT.name());
    }

    @Test
    void carriesDeterministicContextAcrossAdkSession() {
        String appName = "deterministic-agent-mesh";
        String userId = "dev-ui-user";
        String sessionId = "session-context-test";
        InMemorySessionService sessionService = new InMemorySessionService();
        sessionService.createSession(appName, userId, new ConcurrentHashMap<>(), sessionId).blockingGet();
        Runner runner = Runner.builder()
            .agent(new AgentMeshDevUiAgent())
            .appName(appName)
            .artifactService(new InMemoryArtifactService())
            .sessionService(sessionService)
            .memoryService(new InMemoryMemoryService())
            .build();
        try {
            List<Event> firstTurn = run(runner, userId, sessionId, "what should I take for my feveR?");
            assertTrue(text(firstTurn).contains("Status: SUCCESS"));
            assertTrue(text(firstTurn).contains("fever or aches"));
            assertEquals(
                "fever",
                sessionService.getSession(appName, userId, sessionId, java.util.Optional.empty())
                    .blockingGet()
                    .state()
                    .get(ConversationContext.LAST_SYMPTOM_TOPIC_KEY)
            );

            List<Event> secondTurn = run(runner, userId, sessionId, "what if im pregrant");
            String secondText = text(secondTurn);

            assertTrue(secondText.contains("Status: SUCCESS"));
            assertTrue(secondText.contains("Context used: fever"));
            assertTrue(secondText.contains("fever during pregnancy"));
            assertTrue(secondText.contains("Agents: [clinical_retriever]"));
        } finally {
            runner.close().blockingAwait();
        }
    }

    private List<Event> run(Runner runner, String userId, String sessionId, String prompt) {
        List<Event> events = new ArrayList<>();
        for (Event event : runner.runAsync(userId, sessionId, Content.fromParts(Part.fromText(prompt))).blockingIterable()) {
            events.add(event);
        }
        return events;
    }

    private String text(List<Event> events) {
        return events.get(events.size() - 1).stringifyContent();
    }
}
