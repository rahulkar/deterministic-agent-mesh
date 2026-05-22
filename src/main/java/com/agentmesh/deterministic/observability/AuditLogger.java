package com.agentmesh.deterministic.observability;

import com.agentmesh.deterministic.agents.AgentId;
import com.agentmesh.deterministic.schema.ResponseStatus;
import java.time.Instant;
import java.util.List;
import java.util.stream.Collectors;

public final class AuditLogger {
    private AuditLogger() {
    }

    public static void routeSelected(String correlationId, List<AgentId> selectedAgents, double confidence) {
        emit("route_selected", correlationId, "agents=" + names(selectedAgents) + " confidence=" + String.format("%.2f", confidence));
    }

    public static void decision(
        String correlationId,
        ResponseStatus status,
        List<AgentId> selectedAgents,
        String guardDecision,
        boolean llmSkipped
    ) {
        emit(
            "triage_decision",
            correlationId,
            "status=" + status
                + " agents=" + names(selectedAgents)
                + " guard=" + guardDecision
                + " llmSkipped=" + llmSkipped
        );
    }

    private static void emit(String event, String correlationId, String fields) {
        System.out.println("[Audit] ts=" + Instant.now() + " event=" + event + " correlationId=" + correlationId + " " + fields);
    }

    private static String names(List<AgentId> agents) {
        if (agents == null || agents.isEmpty()) {
            return "[]";
        }
        return "[" + agents.stream().map(AgentId::wireName).collect(Collectors.joining(",")) + "]";
    }
}
