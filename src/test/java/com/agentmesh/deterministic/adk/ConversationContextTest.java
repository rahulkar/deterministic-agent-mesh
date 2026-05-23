package com.agentmesh.deterministic.adk;

import com.agentmesh.deterministic.schema.AgentMeshResponse;
import com.agentmesh.deterministic.schema.ResponseStatus;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConversationContextTest {
    @Test
    void storesFeverAfterSuccessfulResponse() {
        Map<String, Object> delta = ConversationContext.stateDelta(
            "what should i take for my fever",
            response(ResponseStatus.SUCCESS, List.of("clinical_retriever"))
        );

        assertEquals("fever", delta.get(ConversationContext.LAST_SYMPTOM_TOPIC_KEY));
        assertEquals("clinical_retriever", delta.get(ConversationContext.LAST_SELECTED_AGENTS_KEY));
    }

    @Test
    void enrichesPregnancyFollowUpWithPriorFever() {
        ConversationContext.ResolvedPrompt resolved = ConversationContext.resolve(
            "what if im pregrant",
            Map.of(ConversationContext.LAST_SYMPTOM_TOPIC_KEY, "fever")
        );

        assertEquals("what if im pregnant regarding fever", resolved.effectivePrompt());
        assertEquals("fever", resolved.contextUsed().orElseThrow());
    }

    @Test
    void doesNotEnrichClearStandalonePrompt() {
        ConversationContext.ResolvedPrompt resolved = ConversationContext.resolve(
            "is ibuprofen safe while pregrant",
            Map.of(ConversationContext.LAST_SYMPTOM_TOPIC_KEY, "fever")
        );

        assertEquals("is ibuprofen safe while pregnant", resolved.effectivePrompt());
        assertFalse(resolved.contextUsed().isPresent());
    }

    @Test
    void doesNotEnrichUnsupportedNonMedicalPrompt() {
        ConversationContext.ResolvedPrompt resolved = ConversationContext.resolve(
            "what about the weather",
            Map.of(ConversationContext.LAST_SYMPTOM_TOPIC_KEY, "fever")
        );

        assertEquals("what about the weather", resolved.effectivePrompt());
        assertFalse(resolved.contextUsed().isPresent());
    }

    @Test
    void doesNotStoreRawPromptOrUnsupportedResult() {
        Map<String, Object> delta = ConversationContext.stateDelta(
            "what about the weather",
            response(ResponseStatus.NO_DATA, List.of())
        );

        assertTrue(delta.isEmpty());
    }

    private AgentMeshResponse response(ResponseStatus status, List<String> selectedAgents) {
        return new AgentMeshResponse(
            status,
            "content",
            "warning",
            selectedAgents,
            0.9,
            "ALLOW",
            "correlation",
            false
        );
    }
}
