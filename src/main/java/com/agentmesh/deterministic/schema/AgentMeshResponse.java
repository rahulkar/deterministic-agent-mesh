package com.agentmesh.deterministic.schema;
import java.util.List;

public record AgentMeshResponse(
    ResponseStatus status,
    String content,
    String warning,
    List<String> selectedAgents,
    double routeConfidence,
    String guardDecision,
    String correlationId,
    boolean llmSkipped
) {}
