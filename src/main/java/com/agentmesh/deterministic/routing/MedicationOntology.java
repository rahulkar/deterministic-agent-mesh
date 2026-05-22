package com.agentmesh.deterministic.routing;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Map;

public record MedicationOntology(
    String version,
    List<DrugEntry> supportedDrugs,
    List<String> clinicalTerms,
    List<String> interactionTerms,
    List<String> dosageTerms,
    List<String> safetyTerms,
    List<String> policyTerms,
    Map<String, String> spellingVariants
) {
    private static final String DEFAULT_RESOURCE = "/agentmesh/medication-taxonomy.json";
    private static final ObjectMapper MAPPER = new ObjectMapper()
        .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    public MedicationOntology {
        supportedDrugs = List.copyOf(supportedDrugs == null ? List.of() : supportedDrugs);
        clinicalTerms = List.copyOf(clinicalTerms == null ? List.of() : clinicalTerms);
        interactionTerms = List.copyOf(interactionTerms == null ? List.of() : interactionTerms);
        dosageTerms = List.copyOf(dosageTerms == null ? List.of() : dosageTerms);
        safetyTerms = List.copyOf(safetyTerms == null ? List.of() : safetyTerms);
        policyTerms = List.copyOf(policyTerms == null ? List.of() : policyTerms);
        spellingVariants = Map.copyOf(spellingVariants == null ? Map.of() : spellingVariants);
    }

    public static MedicationOntology loadDefault() {
        try (InputStream input = MedicationOntology.class.getResourceAsStream(DEFAULT_RESOURCE)) {
            if (input == null) {
                throw new IllegalStateException("Missing medication taxonomy resource " + DEFAULT_RESOURCE);
            }
            return MAPPER.readValue(input, MedicationOntology.class);
        } catch (IOException e) {
            throw new IllegalStateException("Unable to load medication taxonomy " + DEFAULT_RESOURCE, e);
        }
    }

    public record DrugEntry(String canonical, List<String> aliases) {
        public DrugEntry {
            aliases = List.copyOf(aliases == null ? List.of() : aliases);
        }
    }
}
