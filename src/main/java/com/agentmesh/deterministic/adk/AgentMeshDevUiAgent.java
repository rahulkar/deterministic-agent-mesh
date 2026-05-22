package com.agentmesh.deterministic.adk;

import com.agentmesh.deterministic.a2a.A2aRemoteAgentClient;
import com.agentmesh.deterministic.agents.RemoteAgentHosts;
import com.agentmesh.deterministic.mock.MockLiteLlmGateway;
import com.agentmesh.deterministic.orchestrator.AgentMeshOrchestrator;
import com.agentmesh.deterministic.routing.DeterministicAgentRouter;
import com.agentmesh.deterministic.schema.AgentMeshResponse;
import com.agentmesh.deterministic.security.PromptAttackGuard;
import com.google.adk.agents.BaseAgent;
import com.google.adk.agents.InvocationContext;
import com.google.adk.events.Event;
import com.google.genai.types.Content;
import com.google.genai.types.Part;
import io.reactivex.rxjava3.core.Flowable;
import java.util.List;

final class AgentMeshDevUiAgent extends BaseAgent {
    private static final MeshRuntime RUNTIME = new MeshRuntime();

    AgentMeshDevUiAgent() {
        super(
            "deterministic-agent-mesh",
            "Deterministic Agent Mesh ADK Dev UI adapter",
            List.of(),
            List.of(),
            List.of()
        );
    }

    @Override
    protected Flowable<Event> runAsyncImpl(InvocationContext invocationContext) {
        String prompt = invocationContext.userContent()
            .map(Content::text)
            .orElse("");
        AgentMeshResponse response = RUNTIME.orchestrator().executeTriage(prompt);
        return Flowable.just(Event.builder()
            .id(Event.generateEventId())
            .invocationId(invocationContext.invocationId())
            .author(name())
            .content(Content.builder()
                .role("model")
                .parts(Part.fromText(render(prompt, response)))
                .build())
            .turnComplete(true)
            .build());
    }

    @Override
    protected Flowable<Event> runLiveImpl(InvocationContext invocationContext) {
        return runAsyncImpl(invocationContext);
    }

    private String render(String prompt, AgentMeshResponse response) {
        return """
            Deterministic Agent Mesh result

            Query: %s
            Status: %s
            Content: %s
            Warning: %s
            Agents: %s
            Confidence: %.2f
            Guard: %s
            Correlation: %s
            LLM skipped: %s
            """.formatted(
            prompt,
            response.status(),
            response.content(),
            response.warning(),
            response.selectedAgents(),
            response.routeConfidence(),
            response.guardDecision(),
            response.correlationId(),
            response.llmSkipped()
        );
    }

    private static final class MeshRuntime {
        private MockLiteLlmGateway gateway;
        private RemoteAgentHosts hosts;
        private AgentMeshOrchestrator orchestrator;

        synchronized AgentMeshOrchestrator orchestrator() {
            if (orchestrator == null) {
                gateway = new MockLiteLlmGateway(0);
                gateway.start();
                hosts = RemoteAgentHosts.startAllOnRandomPorts(gateway.baseUrl() + "/v1");
                orchestrator = new AgentMeshOrchestrator(
                    new PromptAttackGuard(),
                    new DeterministicAgentRouter(),
                    new A2aRemoteAgentClient(hosts.baseUrls())
                );
                Runtime.getRuntime().addShutdownHook(new Thread(this::stop, "agent-mesh-adk-shutdown"));
            }
            return orchestrator;
        }

        private synchronized void stop() {
            if (hosts != null) {
                hosts.close();
                hosts = null;
            }
            if (gateway != null) {
                gateway.stop();
                gateway = null;
            }
            orchestrator = null;
        }
    }
}
