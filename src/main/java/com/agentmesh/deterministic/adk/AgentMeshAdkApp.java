package com.agentmesh.deterministic.adk;

import com.google.adk.agents.BaseAgent;

public final class AgentMeshAdkApp {
    public static final BaseAgent ROOT_AGENT = new AgentMeshDevUiAgent();

    private AgentMeshAdkApp() {
    }
}
