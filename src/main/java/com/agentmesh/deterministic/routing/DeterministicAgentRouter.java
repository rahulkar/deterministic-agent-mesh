package com.agentmesh.deterministic.routing;

import com.agentmesh.deterministic.agents.AgentId;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;

public class DeterministicAgentRouter {
    private final IntentClassifier primaryClassifier;
    private final IntentClassifier advisoryClassifier;

    public DeterministicAgentRouter() {
        this(new RuleBasedIntentClassifier(), StanfordIntentClassifier.fromSystemProperties());
    }

    public DeterministicAgentRouter(IntentClassifier primaryClassifier) {
        this(primaryClassifier, StanfordIntentClassifier.fromSystemProperties());
    }

    public DeterministicAgentRouter(IntentClassifier primaryClassifier, IntentClassifier advisoryClassifier) {
        this.primaryClassifier = primaryClassifier;
        this.advisoryClassifier = advisoryClassifier;
    }

    public RoutingPlan route(String canonicalPrompt) {
        if (isStandaloneGreeting(canonicalPrompt)) {
            return new RoutingPlan(
                RouteIntent.GREETING,
                0.99,
                List.of(AgentId.GREETING_AGENT),
                "Standalone greeting via deterministic greeting route"
            );
        }

        IntentClassification classification = primaryClassifier.classify(canonicalPrompt);
        if (!classification.supportedMedicalQuery()) {
            return new RoutingPlan(RouteIntent.UNSUPPORTED, classification.confidence(), List.of(), classification.reason());
        }

        LinkedHashSet<AgentId> agents = new LinkedHashSet<>();
        if (classification.complianceIntent()) {
            agents.add(AgentId.COMPLIANCE_GUARD_AGENT);
        }
        if (classification.safetyIntent()) {
            agents.add(AgentId.PHARMACOVIGILANCE_WATCHDOG);
        }
        if (classification.clinicalIntent() || classification.interactionIntent() || classification.dosageIntent()) {
            agents.add(AgentId.CLINICAL_RETRIEVER);
        }
        if (classification.interactionIntent()) {
            agents.add(AgentId.DRUG_INTERACTION_AGENT);
        }
        if (classification.dosageIntent()) {
            agents.add(AgentId.DOSAGE_POLICY_AGENT);
        }
        if (agents.isEmpty()) {
            agents.add(AgentId.CLINICAL_RETRIEVER);
        }

        String reason = classification.reason() + " via " + classification.classifierName();
        if (advisoryClassifier != null) {
            IntentClassification advisory = advisoryClassifier.classify(canonicalPrompt);
            reason = reason + "; advisory=" + advisory.classifierName() + "(" + advisory.reason() + ")";
        }
        return new RoutingPlan(RouteIntent.MEDICATION, classification.confidence(), List.copyOf(agents), reason);
    }

    private boolean isStandaloneGreeting(String canonicalPrompt) {
        String prompt = canonicalPrompt == null ? "" : canonicalPrompt
            .toLowerCase(Locale.ROOT)
            .replaceAll("[^a-z\\s]", " ")
            .replaceAll("\\s+", " ")
            .trim();
        if (prompt.isBlank()) {
            return false;
        }

        return prompt.matches("^(hi|hello|hey|hiya|howdy|greetings|namaste)( there)?( how are you)?$")
            || prompt.matches("^good (morning|afternoon|evening)$")
            || prompt.equals("how are you");
    }
}
