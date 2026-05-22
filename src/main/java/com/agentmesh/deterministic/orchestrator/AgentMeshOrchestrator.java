package com.agentmesh.deterministic.orchestrator;

import com.agentmesh.deterministic.a2a.A2aRemoteAgentClient;
import com.agentmesh.deterministic.agents.AgentId;
import com.agentmesh.deterministic.routing.DeterministicAgentRouter;
import com.agentmesh.deterministic.routing.RoutingPlan;
import com.agentmesh.deterministic.observability.AuditLogger;
import com.agentmesh.deterministic.schema.AgentPayloadException;
import com.agentmesh.deterministic.schema.ClinicalResult;
import com.agentmesh.deterministic.schema.ComplianceResult;
import com.agentmesh.deterministic.schema.DosagePolicyResult;
import com.agentmesh.deterministic.schema.GreetingResult;
import com.agentmesh.deterministic.schema.InteractionResult;
import com.agentmesh.deterministic.schema.AgentMeshResponse;
import com.agentmesh.deterministic.schema.ResponseStatus;
import com.agentmesh.deterministic.schema.SafetyResult;
import com.agentmesh.deterministic.security.GuardDecision;
import com.agentmesh.deterministic.security.PromptAttackGuard;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public class AgentMeshOrchestrator {
    private final PromptAttackGuard promptAttackGuard;
    private final DeterministicAgentRouter router;
    private final A2aRemoteAgentClient a2aClient;

    public AgentMeshOrchestrator() {
        this(new PromptAttackGuard(), new DeterministicAgentRouter(), new A2aRemoteAgentClient());
    }

    public AgentMeshOrchestrator(
        PromptAttackGuard promptAttackGuard,
        DeterministicAgentRouter router,
        A2aRemoteAgentClient a2aClient
    ) {
        this.promptAttackGuard = promptAttackGuard;
        this.router = router;
        this.a2aClient = a2aClient;
    }

    public AgentMeshResponse executeTriage(String userPrompt) {
        String correlationId = UUID.randomUUID().toString();
        GuardDecision guard = promptAttackGuard.inspect(userPrompt);
        if (guard.blocked()) {
            return response(
                ResponseStatus.SECURITY_BLOCKED,
                "Request blocked by deterministic prompt-attack guard.",
                "No downstream agent or model call was made.",
                List.of(),
                1.0,
                guard.reason(),
                correlationId,
                true
            );
        }

        RoutingPlan plan = router.route(guard.canonicalPrompt());
        List<AgentId> selectedAgents = plan.allSelectedAgents();
        if (!plan.supportedQuery()) {
            return response(
                ResponseStatus.NO_DATA,
                "I cannot find pre-approved informational material matching your request.",
                "Only greetings, medication information, and safety triage are supported in this demo.",
                selectedAgents,
                plan.confidence(),
                GuardDecision.disallow("UNSUPPORTED_MEDICAL_INTENT"),
                correlationId,
                true
            );
        }

        AuditLogger.routeSelected(correlationId, selectedAgents, plan.confidence());

        try {
            Map<AgentId, Object> results = invokeSelectedAgents(guard.canonicalPrompt(), correlationId, selectedAgents);
            GreetingResult greeting = (GreetingResult) results.get(AgentId.GREETING_AGENT);
            if (greeting != null && Boolean.TRUE.equals(greeting.handled())) {
                return response(
                    ResponseStatus.SUCCESS,
                    greeting.message(),
                    greeting.disclaimer(),
                    selectedAgents,
                    plan.confidence(),
                    guard.reason(),
                    correlationId,
                    false
                );
            }

            ComplianceResult compliance = (ComplianceResult) results.get(AgentId.COMPLIANCE_GUARD_AGENT);
            SafetyResult safety = (SafetyResult) results.get(AgentId.PHARMACOVIGILANCE_WATCHDOG);

            if (compliance != null && !Boolean.TRUE.equals(compliance.allowed())) {
                return response(
                    ResponseStatus.COMPLIANCE_BLOCKED,
                    "I cannot answer that request within the approved demo policy.",
                    compliance.reason(),
                    selectedAgents,
                    plan.confidence(),
                    GuardDecision.disallow("COMPLIANCE_POLICY"),
                    correlationId,
                    false
                );
            }

            if (safety != null && Boolean.TRUE.equals(safety.adverseEventDetected())) {
                return response(
                    ResponseStatus.SAFETY_ESCALATION,
                    "Please immediately seek professional medical evaluation.",
                    "System Warning: " + safety.riskFactor(),
                    selectedAgents,
                    plan.confidence(),
                    GuardDecision.disallow("SAFETY_ESCALATION"),
                    correlationId,
                    false
                );
            }

            InteractionResult interaction = (InteractionResult) results.get(AgentId.DRUG_INTERACTION_AGENT);
            if (interaction != null && Boolean.TRUE.equals(interaction.interactionDetected())) {
                return response(
                    ResponseStatus.INTERACTION_RISK,
                    interaction.recommendation(),
                    interaction.interaction(),
                    selectedAgents,
                    plan.confidence(),
                    GuardDecision.disallow("DRUG_INTERACTION_RISK"),
                    correlationId,
                    false
                );
            }

            DosagePolicyResult dosage = (DosagePolicyResult) results.get(AgentId.DOSAGE_POLICY_AGENT);
            if (dosage != null && !Boolean.TRUE.equals(dosage.allowed())) {
                return response(
                    ResponseStatus.COMPLIANCE_BLOCKED,
                    "I cannot provide personalized dosing from the supplied information.",
                    dosage.reason(),
                    selectedAgents,
                    plan.confidence(),
                    GuardDecision.disallow("DOSAGE_POLICY"),
                    correlationId,
                    false
                );
            }

            ClinicalResult clinical = (ClinicalResult) results.get(AgentId.CLINICAL_RETRIEVER);
            if (clinical != null && Boolean.TRUE.equals(clinical.matchFound())) {
                return response(
                    ResponseStatus.SUCCESS,
                    clinical.approvedText(),
                    clinical.disclaimer(),
                    selectedAgents,
                    plan.confidence(),
                    guard.reason(),
                    correlationId,
                    false
                );
            }

            return response(
                ResponseStatus.NO_DATA,
                "I cannot find pre-approved informational material matching your request.",
                "Please contact your physician.",
                selectedAgents,
                plan.confidence(),
                GuardDecision.disallow("NO_APPROVED_CONTENT"),
                correlationId,
                false
            );
        } catch (AgentPayloadException e) {
            return response(
                ResponseStatus.AGENT_ERROR,
                "The agent network returned an invalid or unavailable response, so the system failed closed.",
                e.getMessage(),
                selectedAgents,
                plan.confidence(),
                GuardDecision.disallow("AGENT_PAYLOAD_ERROR"),
                correlationId,
                false
            );
        }
    }

    private Map<AgentId, Object> invokeSelectedAgents(String prompt, String correlationId, List<AgentId> selectedAgents) {
        Map<AgentId, Object> results = new EnumMap<>(AgentId.class);
        for (AgentId agentId : selectedAgents) {
            results.put(agentId, switch (agentId) {
                case CLINICAL_RETRIEVER -> a2aClient.invoke(agentId, prompt, correlationId, ClinicalResult.class);
                case PHARMACOVIGILANCE_WATCHDOG -> a2aClient.invoke(agentId, prompt, correlationId, SafetyResult.class);
                case DRUG_INTERACTION_AGENT -> a2aClient.invoke(agentId, prompt, correlationId, InteractionResult.class);
                case COMPLIANCE_GUARD_AGENT -> a2aClient.invoke(agentId, prompt, correlationId, ComplianceResult.class);
                case DOSAGE_POLICY_AGENT -> a2aClient.invoke(agentId, prompt, correlationId, DosagePolicyResult.class);
                case GREETING_AGENT -> a2aClient.invoke(agentId, prompt, correlationId, GreetingResult.class);
            });
        }
        return results;
    }

    private AgentMeshResponse response(
        ResponseStatus status,
        String content,
        String warning,
        List<AgentId> selectedAgents,
        double routeConfidence,
        String guardDecision,
        String correlationId,
        boolean llmSkipped
    ) {
        AuditLogger.decision(correlationId, status, selectedAgents, guardDecision, llmSkipped);
        return new AgentMeshResponse(
            status,
            content,
            warning,
            selectedAgents.stream().map(AgentId::wireName).toList(),
            routeConfidence,
            guardDecision,
            correlationId,
            llmSkipped
        );
    }
}
