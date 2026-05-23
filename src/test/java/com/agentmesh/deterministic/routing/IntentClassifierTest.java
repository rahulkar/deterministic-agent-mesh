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
    void detectsBroaderFreeTextMedicationLanguage() {
        IntentClassification interaction = classifier.classify("Can I mix ibuprofen and aspirin at the same time?");
        IntentClassification symptoms = classifier.classify("What OTC relief helps body aches and congestion?");
        IntentClassification pediatricDose = classifier.classify("How much Tylenol for my kid by weight?");
        IntentClassification policy = classifier.classify("Do I need antibiotics for this cough?");

        assertTrue(interaction.supportedMedicalQuery());
        assertTrue(interaction.interactionIntent());
        assertTrue(symptoms.supportedMedicalQuery());
        assertTrue(symptoms.clinicalIntent());
        assertTrue(pediatricDose.supportedMedicalQuery());
        assertTrue(pediatricDose.dosageIntent());
        assertTrue(policy.supportedMedicalQuery());
        assertTrue(policy.complianceIntent());
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
