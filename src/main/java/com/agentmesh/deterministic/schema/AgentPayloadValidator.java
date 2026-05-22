package com.agentmesh.deterministic.schema;

public final class AgentPayloadValidator {
    private AgentPayloadValidator() {
    }

    public static void validate(String agentName, Object payload) {
        if (payload instanceof SafetyResult safety) {
            require(agentName, "adverseEventDetected", safety.adverseEventDetected());
            requireText(agentName, "riskFactor", safety.riskFactor());
            return;
        }
        if (payload instanceof ClinicalResult clinical) {
            require(agentName, "matchFound", clinical.matchFound());
            if (Boolean.TRUE.equals(clinical.matchFound())) {
                requireText(agentName, "approvedText", clinical.approvedText());
                requireText(agentName, "disclaimer", clinical.disclaimer());
            }
            return;
        }
        if (payload instanceof ComplianceResult compliance) {
            require(agentName, "allowed", compliance.allowed());
            require(agentName, "unsupportedTopic", compliance.unsupportedTopic());
            requireText(agentName, "reason", compliance.reason());
            return;
        }
        if (payload instanceof InteractionResult interaction) {
            require(agentName, "interactionDetected", interaction.interactionDetected());
            if (Boolean.TRUE.equals(interaction.interactionDetected())) {
                requireText(agentName, "interaction", interaction.interaction());
                requireText(agentName, "recommendation", interaction.recommendation());
            }
            return;
        }
        if (payload instanceof DosagePolicyResult dosage) {
            require(agentName, "allowed", dosage.allowed());
            require(agentName, "missingRequiredContext", dosage.missingRequiredContext());
            requireText(agentName, "reason", dosage.reason());
            return;
        }
        if (payload instanceof GreetingResult greeting) {
            require(agentName, "handled", greeting.handled());
            if (Boolean.TRUE.equals(greeting.handled())) {
                requireText(agentName, "message", greeting.message());
                requireText(agentName, "disclaimer", greeting.disclaimer());
            }
            return;
        }
        throw new AgentPayloadException("Unsupported payload type from " + agentName);
    }

    private static void require(String agentName, String field, Boolean value) {
        if (value == null) {
            throw new AgentPayloadException(agentName + " returned malformed payload: missing " + field);
        }
    }

    private static void requireText(String agentName, String field, String value) {
        if (value == null || value.isBlank()) {
            throw new AgentPayloadException(agentName + " returned malformed payload: missing " + field);
        }
    }
}
