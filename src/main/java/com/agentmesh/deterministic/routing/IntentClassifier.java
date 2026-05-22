package com.agentmesh.deterministic.routing;

public interface IntentClassifier {
    String name();

    IntentClassification classify(String canonicalPrompt);
}
