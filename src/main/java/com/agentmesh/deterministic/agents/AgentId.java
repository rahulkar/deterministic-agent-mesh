package com.agentmesh.deterministic.agents;

public enum AgentId {
    CLINICAL_RETRIEVER("clinical_retriever", 9001, "Approved medication information only"),
    PHARMACOVIGILANCE_WATCHDOG("pharmacovigilance_watchdog", 9002, "Adverse event and emergency detection"),
    DRUG_INTERACTION_AGENT("drug_interaction_agent", 9003, "Medication interaction detection"),
    COMPLIANCE_GUARD_AGENT("compliance_guard_agent", 9004, "Policy and unsupported-topic guard"),
    DOSAGE_POLICY_AGENT("dosage_policy_agent", 9005, "Personalized dosage policy checks"),
    GREETING_AGENT("greeting_agent", 9006, "Friendly deterministic greeting handler");

    private final String wireName;
    private final int port;
    private final String description;

    AgentId(String wireName, int port, String description) {
        this.wireName = wireName;
        this.port = port;
        this.description = description;
    }

    public String wireName() {
        return wireName;
    }

    public int port() {
        return port;
    }

    public String description() {
        return description;
    }

    public String baseUrl() {
        return "http://localhost:" + port;
    }
}
