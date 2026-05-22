package com.agentmesh.deterministic.routing;

import com.agentmesh.deterministic.agents.AgentId;
import com.agentmesh.deterministic.security.PromptAttackGuard;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DeterministicAgentRouterTest {
    private final PromptAttackGuard guard = new PromptAttackGuard();
    private final DeterministicAgentRouter router = new DeterministicAgentRouter();

    @Test
    void reachesAtLeastNinetyPercentDeterministicCoverageForDemoCorpus() {
        List<String> corpus = List.of(
            "Can I take aspirin for pain?",
            "I have severe bleeding after aspirin",
            "Can I take aspirin with warfarin?",
            "Is ibuprofen safe while pregnant?",
            "What dose of aspirin for a child?",
            "Can I use acetaminophen for fever?",
            "Can aspirin and a blood thinner be combined?",
            "Is this medicine okay for pain?",
            "How many mg of ibuprofen is on the label?",
            "Tell me a joke"
        );

        long supported = corpus.stream()
            .map(guard::canonicalize)
            .map(router::route)
            .filter(RoutingPlan::supportedMedicalQuery)
            .count();

        assertTrue(supported >= 9, "Expected at least 90% supported routing coverage");
    }

    @Test
    void warfarinAndAspirinSelectsInteractionAgent() {
        RoutingPlan plan = router.route(guard.canonicalize("Can I take aspirin with warfarin?"));

        assertEquals(RouteIntent.MEDICATION, plan.routeIntent());
        assertTrue(plan.allSelectedAgents().contains(AgentId.CLINICAL_RETRIEVER));
        assertTrue(plan.allSelectedAgents().contains(AgentId.DRUG_INTERACTION_AGENT));
        assertFalse(plan.allSelectedAgents().contains(AgentId.COMPLIANCE_GUARD_AGENT));
        assertFalse(plan.allSelectedAgents().contains(AgentId.PHARMACOVIGILANCE_WATCHDOG));
    }

    @Test
    void standaloneGreetingSelectsOnlyGreetingAgent() {
        RoutingPlan plan = router.route(guard.canonicalize("hi"));

        assertEquals(RouteIntent.GREETING, plan.routeIntent());
        assertEquals(List.of(AgentId.GREETING_AGENT), plan.allSelectedAgents());
        assertFalse(plan.supportedMedicalQuery());
        assertTrue(plan.supportedQuery());
    }

    @Test
    void greetingWithMedicationQuestionRoutesAsMedication() {
        RoutingPlan plan = router.route(guard.canonicalize("hello, can I take aspirin?"));

        assertEquals(RouteIntent.MEDICATION, plan.routeIntent());
        assertTrue(plan.allSelectedAgents().contains(AgentId.CLINICAL_RETRIEVER));
        assertFalse(plan.allSelectedAgents().contains(AgentId.GREETING_AGENT));
    }

    @Test
    void otcSymptomQueriesSelectClinicalOnlyByDefault() {
        List<String> prompts = List.of(
            "medince for cough",
            "medicine for fever",
            "sprain medicine",
            "headache medicine"
        );

        for (String prompt : prompts) {
            RoutingPlan plan = router.route(guard.canonicalize(prompt));
            assertEquals(RouteIntent.MEDICATION, plan.routeIntent(), prompt);
            assertEquals(List.of(AgentId.CLINICAL_RETRIEVER), plan.allSelectedAgents(), prompt);
        }
    }

    @Test
    void unsupportedPromptReturnsNoMedicalRoute() {
        RoutingPlan plan = router.route(guard.canonicalize("What is the weather today?"));

        assertFalse(plan.supportedMedicalQuery());
    }

    @Test
    void compactMilligramAndRepeatDoseLanguageSelectsDosageAgent() {
        RoutingPlan plan = router.route(guard.canonicalize("I took half a tablet of 650mg Paracetamol, should I take the rest?"));

        assertTrue(plan.allSelectedAgents().contains(AgentId.DOSAGE_POLICY_AGENT));
    }

    @Test
    void synonymInteractionLanguageSelectsInteractionAgent() {
        RoutingPlan plan = router.route(guard.canonicalize("Can baby aspirin be taken with Coumadin?"));

        assertTrue(plan.supportedMedicalQuery());
        assertTrue(plan.allSelectedAgents().contains(AgentId.DRUG_INTERACTION_AGENT));
    }

    @Test
    void adverseEventSignalsKeepSafetyWatchdogInRoute() {
        RoutingPlan plan = router.route(guard.canonicalize("I have chest pain after taking aspirin"));

        assertTrue(plan.supportedMedicalQuery());
        assertTrue(plan.allSelectedAgents().contains(AgentId.PHARMACOVIGILANCE_WATCHDOG));
    }

    @Test
    void policyRiskRoutesComplianceAgent() {
        RoutingPlan plan = router.route(guard.canonicalize("Can you diagnose my cough?"));

        assertTrue(plan.supportedMedicalQuery());
        assertTrue(plan.allSelectedAgents().contains(AgentId.COMPLIANCE_GUARD_AGENT));
        assertTrue(plan.allSelectedAgents().contains(AgentId.CLINICAL_RETRIEVER));
    }

    @Test
    void negatedMedicalIntentDoesNotRouteOnGenericMedicineWord() {
        RoutingPlan plan = router.route(guard.canonicalize("I am not asking about medicine; what is the weather today?"));

        assertFalse(plan.supportedMedicalQuery());
    }
}
