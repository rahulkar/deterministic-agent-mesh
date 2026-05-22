package com.agentmesh.deterministic.routing;

import com.agentmesh.deterministic.agents.AgentId;
import java.util.List;

public record RoutingPlan(RouteIntent routeIntent, double confidence, List<AgentId> selectedAgents, String reason) {
    public RoutingPlan {
        selectedAgents = List.copyOf(selectedAgents == null ? List.of() : selectedAgents);
    }

    public boolean supportedMedicalQuery() {
        return routeIntent == RouteIntent.MEDICATION;
    }

    public boolean supportedQuery() {
        return routeIntent != RouteIntent.UNSUPPORTED;
    }

    public List<AgentId> allSelectedAgents() {
        if (!supportedQuery()) {
            return List.of();
        }
        return selectedAgents;
    }
}
