package com.agentmesh.deterministic.security;

public record GuardDecision(boolean blocked, String reason, String canonicalPrompt) {
    public static GuardDecision allow(String canonicalPrompt) {
        return new GuardDecision(false, "ALLOW", canonicalPrompt);
    }

    public static GuardDecision block(String reason, String canonicalPrompt) {
        return new GuardDecision(true, disallow(reason), canonicalPrompt);
    }

    public static String disallow(String reason) {
        if (reason == null || reason.isBlank()) {
            return "DISALLOW";
        }
        return reason.startsWith("DISALLOW") ? reason : "DISALLOW:" + reason;
    }
}
