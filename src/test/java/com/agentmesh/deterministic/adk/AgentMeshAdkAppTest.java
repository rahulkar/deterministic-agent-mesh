package com.agentmesh.deterministic.adk;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class AgentMeshAdkAppTest {
    @Test
    void exposesRootAgentForGoogleAdkDevUiDiscovery() {
        assertNotNull(AgentMeshAdkApp.ROOT_AGENT);
        assertEquals("deterministic-agent-mesh", AgentMeshAdkApp.ROOT_AGENT.name());
    }
}
