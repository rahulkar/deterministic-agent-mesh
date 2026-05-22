package com.agentmesh.deterministic.schema;

public record ComplianceResult(Boolean allowed, Boolean unsupportedTopic, String reason) {}
