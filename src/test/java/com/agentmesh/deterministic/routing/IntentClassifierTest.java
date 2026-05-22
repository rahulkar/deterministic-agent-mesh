package com.agentmesh.deterministic.routing;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IntentClassifierTest {
    private final IntentClassifier classifier = new RuleBasedIntentClassifier();

    @Test
    void detectsSynonymsAndSpellingVariantsFromVersionedTaxonomy() {
        IntentClassification classification = classifier.classify("Can I take asprin with Coumadin?");

        assertTrue(classification.supportedMedicalQuery());
        assertTrue(classification.interactionIntent());
        assertTrue(classification.matchedDrugs().contains("aspirin"));
        assertTrue(classification.matchedDrugs().contains("warfarin"));
    }

    @Test
    void detectsDosageAndPediatricLanguage() {
        IntentClassification classification = classifier.classify("How many mg of Tylenol for a child by weight?");

        assertTrue(classification.supportedMedicalQuery());
        assertTrue(classification.dosageIntent());
        assertTrue(classification.matchedDrugs().contains("acetaminophen"));
    }

    @Test
    void detectsCommonOtcSymptomsAndSpellingVariants() {
        IntentClassification classification = classifier.classify("medince for cough");

        assertTrue(classification.supportedMedicalQuery());
        assertTrue(classification.clinicalIntent());
        assertTrue(classification.matchedSignals().contains("cough"));
    }

    @Test
    void detectsPolicyRiskLanguage() {
        IntentClassification classification = classifier.classify("Can you diagnose my cough?");

        assertTrue(classification.supportedMedicalQuery());
        assertTrue(classification.complianceIntent());
    }

    @Test
    void keepsNonMedicalAndNegatedPromptsUnsupported() {
        assertFalse(classifier.classify("What is the weather today?").supportedMedicalQuery());
        assertFalse(classifier.classify("I am not asking about medicine; tell me a joke").supportedMedicalQuery());
    }

    @Test
    void stanfordClassifierRemainsAdvisoryAndDisabledByDefault() {
        IntentClassification classification = StanfordIntentClassifier.fromSystemProperties().classify("Can I take aspirin?");

        assertFalse(classification.supportedMedicalQuery());
        assertTrue(classification.reason().contains("disabled") || classification.reason().contains("enabled but"));
    }
}
