package com.agentmesh.deterministic;

import com.agentmesh.deterministic.agents.AgentId;
import com.agentmesh.deterministic.agents.RemoteAgentHosts;
import com.agentmesh.deterministic.mock.MockLiteLlmGateway;
import com.google.adk.web.AdkWebServer;
import java.util.Map;

public final class StackLauncher {
    private static final int MOCK_GATEWAY_PORT = 8080;
    private static final int DEFAULT_ADK_PORT = 8000;

    private StackLauncher() {
    }

    public static void main(String[] args) {
        int adkPort = adkPort();
        MockLiteLlmGateway gateway = new MockLiteLlmGateway(MOCK_GATEWAY_PORT);
        gateway.start();
        RemoteAgentHosts hosts = RemoteAgentHosts.startAll(gateway.baseUrl() + "/v1");
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            hosts.close();
            gateway.stop();
        }, "agent-mesh-stack-shutdown"));

        printBanner(adkPort, hosts.baseUrls());
        System.setProperty("agentmesh.adk.external-runtime", "true");
        System.setProperty("agentmesh.adk.start-runtime-on-load", "true");
        AdkWebServer.main(new String[] {
            "--adk.agents.source-dir=.",
            "--server.port=" + adkPort
        });
    }

    private static int adkPort() {
        String value = System.getenv().getOrDefault("SERVER_PORT", String.valueOf(DEFAULT_ADK_PORT));
        return Integer.parseInt(value);
    }

    private static void printBanner(int adkPort, Map<AgentId, String> baseUrls) {
        System.out.println("Starting Deterministic Agent Mesh stack...");
        System.out.println("Mock LiteLLM gateway: http://localhost:" + MOCK_GATEWAY_PORT + "/v1");
        System.out.println("ADK Dev UI: http://localhost:" + adkPort);
        System.out.println();
        System.out.println("Remote A2A Agent Cards:");
        for (AgentId agentId : AgentId.values()) {
            System.out.printf("  %-30s %s/.well-known/agent-card.json%n", agentId.wireName() + ":", baseUrls.get(agentId));
        }
        System.out.println();
    }
}
