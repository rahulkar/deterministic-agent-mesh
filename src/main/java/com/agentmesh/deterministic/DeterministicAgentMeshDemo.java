package com.agentmesh.deterministic;

import com.agentmesh.deterministic.agents.RemoteAgentHosts;
import com.agentmesh.deterministic.mock.MockLiteLlmGateway;
import com.agentmesh.deterministic.orchestrator.AgentMeshOrchestrator;
import com.agentmesh.deterministic.schema.AgentMeshResponse;

import java.util.List;

public class DeterministicAgentMeshDemo {

    public static void main(String[] args) {
        int liteLlmPort = Integer.getInteger("litellm.mock.port", 0);
        MockLiteLlmGateway gateway = new MockLiteLlmGateway(liteLlmPort);
        gateway.start();

        try (RemoteAgentHosts ignored = RemoteAgentHosts.startAll(gateway.baseUrl() + "/v1")) {
            AgentMeshOrchestrator orchestrator = new AgentMeshOrchestrator();
            List<String> demoQueries = List.of(
                    "hi",
                    "medince for cough",
                    "what medicine can i take for my fever",
                    "sprain medicine",
                    "headache medicine",
                    "hello, can I take aspirin?",
                    "Can I take aspirin with warfarin?",
                    "I took half a tablet of 650mg Paracetamol, but still unwell should I take the rest?",
                    "I took 325mg of aspirin but I have severe bleeding. What should I do?",
                    "What fruits to eat for a better immunity?"
            );

            for (String query : demoQueries) {
                AgentMeshResponse response = orchestrator.executeTriage(query);
                print(query, response);
            }
        } finally {
            gateway.stop();
        }
    }

    private static void print(String query, AgentMeshResponse response) {
        System.out.println("\n=== DETERMINISTIC AGENT MESH RESULT ===");
        System.out.println("Query       : " + query);
        System.out.println("Status      : " + response.status());
        System.out.println("Content     : " + response.content());
        System.out.println("Warning     : " + response.warning());
        System.out.println("Agents      : " + response.selectedAgents());
        System.out.printf("Confidence  : %.2f%n", response.routeConfidence());
        System.out.println("Guard       : " + response.guardDecision());
        System.out.println("Correlation : " + response.correlationId());
        System.out.println("LLM skipped : " + response.llmSkipped());
        System.out.println("=============================\n");
    }
}
