package com.agentmesh.deterministic.schema;

public record DosagePolicyResult(Boolean allowed, Boolean missingRequiredContext, String reason) {}
