package com.agentmesh.deterministic.routing;

import java.nio.file.Files;
import java.nio.file.Path;

public final class StanfordIntentClassifier implements IntentClassifier {
    private static final String MODEL_PROPERTY = "agentmesh.stanford.model";
    private static final String ENABLED_PROPERTY = "agentmesh.stanford.enabled";

    private final boolean enabled;
    private final String reason;

    private StanfordIntentClassifier(boolean enabled, String reason) {
        this.enabled = enabled;
        this.reason = reason;
    }

    public static StanfordIntentClassifier fromSystemProperties() {
        if (!Boolean.getBoolean(ENABLED_PROPERTY)) {
            return new StanfordIntentClassifier(false, "disabled; set -D" + ENABLED_PROPERTY + "=true to enable advisory mode");
        }
        String model = System.getProperty(MODEL_PROPERTY, "");
        if (model.isBlank()) {
            return new StanfordIntentClassifier(false, "enabled but no -D" + MODEL_PROPERTY + " path was provided");
        }
        if (!Files.isRegularFile(Path.of(model))) {
            return new StanfordIntentClassifier(false, "enabled but model file does not exist: " + model);
        }
        try {
            Class.forName("edu.stanford.nlp.classify.ColumnDataClassifier");
            Class.forName("edu.stanford.nlp.classify.LinearClassifier");
            return new StanfordIntentClassifier(true, "Stanford CoreNLP classifier classes and model are available");
        } catch (ClassNotFoundException e) {
            return new StanfordIntentClassifier(false, "Stanford CoreNLP is not on the classpath; enable the Maven profile before using advisory mode");
        }
    }

    @Override
    public String name() {
        return "stanford-classifier-advisory";
    }

    @Override
    public IntentClassification classify(String canonicalPrompt) {
        if (!enabled) {
            return IntentClassification.unsupported(name(), reason);
        }

        // The Stanford classifier is intentionally advisory only. Production deployments should
        // provide the trained model and evaluation harness before mapping labels into this record.
        return IntentClassification.unsupported(
            name(),
            "Stanford advisory adapter is available, but deterministic taxonomy remains authoritative"
        );
    }
}
