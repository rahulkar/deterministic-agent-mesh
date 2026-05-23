package com.agentmesh.deterministic.adk;

import com.agentmesh.deterministic.schema.AgentMeshResponse;
import com.agentmesh.deterministic.schema.ResponseStatus;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

final class ConversationContext {
    static final String LAST_SYMPTOM_TOPIC_KEY = "agentmesh.lastSymptomTopic";
    static final String LAST_DRUG_TOPIC_KEY = "agentmesh.lastDrugTopic";
    static final String LAST_SELECTED_AGENTS_KEY = "agentmesh.lastSelectedAgents";

    private static final Map<String, String> SPELLING_VARIANTS = Map.ofEntries(
        Map.entry("asprin", "aspirin"),
        Map.entry("paracetemol", "paracetamol"),
        Map.entry("ibuprofin", "ibuprofen"),
        Map.entry("naproxin", "naproxen"),
        Map.entry("medince", "medicine"),
        Map.entry("medicne", "medicine"),
        Map.entry("medcine", "medicine"),
        Map.entry("caugh", "cough"),
        Map.entry("feaver", "fever"),
        Map.entry("pregrant", "pregnant"),
        Map.entry("pregant", "pregnant"),
        Map.entry("pregnent", "pregnant"),
        Map.entry("pregnnt", "pregnant"),
        Map.entry("headach", "headache")
    );
    private static final Map<String, List<String>> SYMPTOM_TOPICS = orderedMap(
        Map.entry("fever", List.of("fever", "high fever")),
        Map.entry("cough", List.of("cough")),
        Map.entry("cold", List.of("cold", "mucus", "congestion")),
        Map.entry("sprain", List.of("sprain", "strain")),
        Map.entry("headache", List.of("headache", "severe headache")),
        Map.entry("pain", List.of("pain", "ache", "aches", "body aches"))
    );
    private static final Map<String, List<String>> DRUG_TOPICS = orderedMap(
        Map.entry("aspirin", List.of("aspirin", "baby aspirin", "asa", "acetylsalicylic acid")),
        Map.entry("ibuprofen", List.of("ibuprofen", "advil", "motrin")),
        Map.entry("acetaminophen", List.of("acetaminophen", "paracetamol", "tylenol")),
        Map.entry("naproxen", List.of("naproxen", "aleve")),
        Map.entry("warfarin", List.of("warfarin", "coumadin")),
        Map.entry("dextromethorphan", List.of("dextromethorphan", "dxm")),
        Map.entry("guaifenesin", List.of("guaifenesin", "expectorant"))
    );
    private static final List<String> MEDICAL_QUALIFIERS = List.of(
        "pregnant",
        "pregnancy",
        "child",
        "kid",
        "pediatric",
        "breastfeeding",
        "breast feeding"
    );
    private static final List<String> NON_MEDICAL_TOPICS = List.of(
        "weather",
        "joke",
        "sports",
        "stock",
        "movie",
        "recipe",
        "capital"
    );

    private ConversationContext() {
    }

    static ResolvedPrompt resolve(String prompt, Map<String, Object> state) {
        String normalized = normalize(prompt);
        Optional<String> currentSymptom = firstTopic(normalized, SYMPTOM_TOPICS);
        Optional<String> currentDrug = firstTopic(normalized, DRUG_TOPICS);
        Optional<String> storedTopic = storedTopic(state);
        if (shouldUseStoredContext(normalized, currentSymptom, currentDrug) && storedTopic.isPresent()) {
            String topic = storedTopic.get();
            return new ResolvedPrompt(normalized + " regarding " + topic, Optional.of(topic));
        }
        return new ResolvedPrompt(normalized, Optional.empty());
    }

    static Map<String, Object> stateDelta(String effectivePrompt, AgentMeshResponse response) {
        Map<String, Object> delta = new LinkedHashMap<>();
        if (!storesContext(response)) {
            return delta;
        }
        String normalized = normalize(effectivePrompt);
        firstTopic(normalized, SYMPTOM_TOPICS).ifPresent(topic -> delta.put(LAST_SYMPTOM_TOPIC_KEY, topic));
        firstTopic(normalized, DRUG_TOPICS).ifPresent(topic -> delta.put(LAST_DRUG_TOPIC_KEY, topic));
        if (response.status() == ResponseStatus.SUCCESS && !response.selectedAgents().isEmpty()) {
            delta.put(LAST_SELECTED_AGENTS_KEY, String.join(",", response.selectedAgents()));
        }
        return delta;
    }

    static String normalize(String value) {
        String normalized = value == null ? "" : value
            .toLowerCase(Locale.ROOT)
            .replaceAll("\\s+", " ")
            .trim();
        for (Map.Entry<String, String> entry : SPELLING_VARIANTS.entrySet()) {
            normalized = normalized.replaceAll("\\b" + Pattern.quote(entry.getKey()) + "\\b", entry.getValue());
        }
        return normalized;
    }

    private static Optional<String> storedTopic(Map<String, Object> state) {
        Optional<String> symptom = stateValue(state, LAST_SYMPTOM_TOPIC_KEY);
        return symptom.isPresent() ? symptom : stateValue(state, LAST_DRUG_TOPIC_KEY);
    }

    private static Optional<String> stateValue(Map<String, Object> state, String key) {
        if (state == null) {
            return Optional.empty();
        }
        Object value = state.get(key);
        if (value instanceof String text && !text.isBlank()) {
            return Optional.of(text);
        }
        return Optional.empty();
    }

    private static boolean shouldUseStoredContext(
        String normalizedPrompt,
        Optional<String> currentSymptom,
        Optional<String> currentDrug
    ) {
        if (normalizedPrompt.isBlank() || currentSymptom.isPresent() || currentDrug.isPresent()) {
            return false;
        }
        if (containsAny(normalizedPrompt, NON_MEDICAL_TOPICS)) {
            return false;
        }
        return isFollowUpShape(normalizedPrompt) && containsAny(normalizedPrompt, MEDICAL_QUALIFIERS);
    }

    private static boolean isFollowUpShape(String normalizedPrompt) {
        return normalizedPrompt.startsWith("what if")
            || normalizedPrompt.startsWith("what about")
            || normalizedPrompt.startsWith("how about")
            || normalizedPrompt.startsWith("and if")
            || normalizedPrompt.contains(" if im ")
            || normalizedPrompt.contains(" if i'm ")
            || normalizedPrompt.contains(" if i am ")
            || normalizedPrompt.startsWith("if im ")
            || normalizedPrompt.startsWith("if i'm ")
            || normalizedPrompt.startsWith("if i am ");
    }

    private static boolean storesContext(AgentMeshResponse response) {
        return response != null
            && response.status() != ResponseStatus.NO_DATA
            && response.status() != ResponseStatus.SECURITY_BLOCKED
            && response.status() != ResponseStatus.AGENT_ERROR;
    }

    private static Optional<String> firstTopic(String normalizedPrompt, Map<String, List<String>> topics) {
        for (Map.Entry<String, List<String>> entry : topics.entrySet()) {
            for (String term : entry.getValue()) {
                if (containsTerm(normalizedPrompt, term)) {
                    return Optional.of(entry.getKey());
                }
            }
        }
        return Optional.empty();
    }

    private static boolean containsAny(String normalizedPrompt, List<String> terms) {
        for (String term : terms) {
            if (containsTerm(normalizedPrompt, term)) {
                return true;
            }
        }
        return false;
    }

    private static boolean containsTerm(String normalizedPrompt, String term) {
        if (term.contains(" ")) {
            return normalizedPrompt.contains(term);
        }
        return Pattern.compile("\\b" + Pattern.quote(term) + "\\b").matcher(normalizedPrompt).find();
    }

    @SafeVarargs
    private static Map<String, List<String>> orderedMap(Map.Entry<String, List<String>>... entries) {
        Map<String, List<String>> values = new LinkedHashMap<>();
        for (Map.Entry<String, List<String>> entry : entries) {
            values.put(entry.getKey(), entry.getValue());
        }
        return values;
    }

    record ResolvedPrompt(String effectivePrompt, Optional<String> contextUsed) {
    }
}
