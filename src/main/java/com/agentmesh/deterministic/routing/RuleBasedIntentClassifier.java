package com.agentmesh.deterministic.routing;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

public final class RuleBasedIntentClassifier implements IntentClassifier {
    private final MedicationOntology ontology;

    public RuleBasedIntentClassifier() {
        this(MedicationOntology.loadDefault());
    }

    public RuleBasedIntentClassifier(MedicationOntology ontology) {
        this.ontology = ontology;
    }

    @Override
    public String name() {
        return "rule-based-taxonomy:" + ontology.version();
    }

    @Override
    public IntentClassification classify(String canonicalPrompt) {
        String prompt = normalize(canonicalPrompt);
        if (prompt.isBlank()) {
            return IntentClassification.unsupported(name(), "Empty prompt after canonicalization");
        }

        Set<String> matchedDrugs = matchedDrugs(prompt);
        List<String> clinicalSignals = matchedTerms(prompt, ontology.clinicalTerms());
        List<String> interactionSignals = matchedTerms(prompt, ontology.interactionTerms());
        List<String> dosageSignals = matchedTerms(prompt, ontology.dosageTerms());
        List<String> safetySignals = matchedTerms(prompt, ontology.safetyTerms());
        List<String> policySignals = matchedTerms(prompt, ontology.policyTerms());

        if (matchedDrugs.isEmpty() && looksLikeNegatedMedicalIntent(prompt)) {
            return IntentClassification.unsupported(name(), "Prompt explicitly negated medical intent");
        }

        boolean hasMedicalSignal = !matchedDrugs.isEmpty()
            || !clinicalSignals.isEmpty()
            || !dosageSignals.isEmpty()
            || !safetySignals.isEmpty()
            || !policySignals.isEmpty();
        if (!hasMedicalSignal) {
            return IntentClassification.unsupported(name(), "No supported medication ontology match");
        }

        boolean clinicalIntent = !matchedDrugs.isEmpty() || !clinicalSignals.isEmpty();
        boolean interactionIntent = !interactionSignals.isEmpty() && mentionsInteractionCounterparty(prompt, matchedDrugs);
        boolean dosageIntent = !dosageSignals.isEmpty();
        boolean safetyIntent = !safetySignals.isEmpty();
        boolean complianceIntent = !policySignals.isEmpty();
        List<String> matchedSignals = new ArrayList<>();
        matchedSignals.addAll(clinicalSignals);
        matchedSignals.addAll(interactionSignals);
        matchedSignals.addAll(dosageSignals);
        matchedSignals.addAll(safetySignals);
        matchedSignals.addAll(policySignals);

        int intentCount = 0;
        if (clinicalIntent) {
            intentCount++;
        }
        if (interactionIntent) {
            intentCount++;
        }
        if (dosageIntent) {
            intentCount++;
        }
        if (safetyIntent) {
            intentCount++;
        }
        if (complianceIntent) {
            intentCount++;
        }
        double confidence = Math.min(0.99, 0.82 + (intentCount * 0.04) + Math.min(0.08, matchedDrugs.size() * 0.04));
        return new IntentClassification(
            true,
            confidence,
            clinicalIntent,
            interactionIntent,
            dosageIntent,
            safetyIntent,
            complianceIntent,
            List.copyOf(matchedDrugs),
            List.copyOf(new LinkedHashSet<>(matchedSignals)),
            "Versioned medication taxonomy match",
            name()
        );
    }

    private String normalize(String value) {
        String normalized = value == null ? "" : value.toLowerCase(Locale.ROOT).replaceAll("\\s+", " ").trim();
        for (MapEntry entry : ontology.spellingVariants().entrySet().stream()
            .map(e -> new MapEntry(e.getKey(), e.getValue()))
            .toList()) {
            normalized = normalized.replaceAll("\\b" + Pattern.quote(entry.key()) + "\\b", entry.value());
        }
        return normalized;
    }

    private Set<String> matchedDrugs(String prompt) {
        Set<String> matches = new LinkedHashSet<>();
        for (MedicationOntology.DrugEntry drug : ontology.supportedDrugs()) {
            for (String alias : drug.aliases()) {
                if (containsTerm(prompt, alias)) {
                    matches.add(drug.canonical());
                    break;
                }
            }
        }
        return matches;
    }

    private List<String> matchedTerms(String prompt, List<String> terms) {
        List<String> matches = new ArrayList<>();
        for (String term : terms) {
            if (containsTerm(prompt, term)) {
                matches.add(term);
            }
        }
        return matches;
    }

    private boolean mentionsInteractionCounterparty(String prompt, Set<String> matchedDrugs) {
        return matchedDrugs.size() >= 2
            || containsTerm(prompt, "blood thinner")
            || containsTerm(prompt, "anticoagulant")
            || containsTerm(prompt, "warfarin");
    }

    private boolean looksLikeNegatedMedicalIntent(String prompt) {
        return prompt.contains("not asking about medicine")
            || prompt.contains("not asking for medicine")
            || prompt.contains("not asking for medical")
            || prompt.contains("not a medical question");
    }

    private boolean containsTerm(String prompt, String term) {
        if (term == null || term.isBlank()) {
            return false;
        }
        String normalizedTerm = term.toLowerCase(Locale.ROOT).replaceAll("\\s+", " ").trim();
        if (normalizedTerm.contains(" ")) {
            return prompt.contains(normalizedTerm);
        }
        return Pattern.compile("\\b" + Pattern.quote(normalizedTerm) + "\\b").matcher(prompt).find();
    }

    private record MapEntry(String key, String value) {
    }
}
