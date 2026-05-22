package com.agentmesh.deterministic.routing;

import java.util.List;

public record IntentClassification(
    boolean supportedMedicalQuery,
    double confidence,
    boolean clinicalIntent,
    boolean interactionIntent,
    boolean dosageIntent,
    boolean safetyIntent,
    boolean complianceIntent,
    List<String> matchedDrugs,
    List<String> matchedSignals,
    String reason,
    String classifierName
) {
    public IntentClassification {
        matchedDrugs = List.copyOf(matchedDrugs == null ? List.of() : matchedDrugs);
        matchedSignals = List.copyOf(matchedSignals == null ? List.of() : matchedSignals);
    }

    public static IntentClassification unsupported(String classifierName, String reason) {
        return new IntentClassification(
            false,
            0.20,
            false,
            false,
            false,
            false,
            false,
            List.of(),
            List.of(),
            reason,
            classifierName
        );
    }

    public IntentClassification withReason(String updatedReason) {
        return new IntentClassification(
            supportedMedicalQuery,
            confidence,
            clinicalIntent,
            interactionIntent,
            dosageIntent,
            safetyIntent,
            complianceIntent,
            matchedDrugs,
            matchedSignals,
            updatedReason,
            classifierName
        );
    }
}
