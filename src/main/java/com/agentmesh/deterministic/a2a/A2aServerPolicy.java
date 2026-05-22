package com.agentmesh.deterministic.a2a;

import com.sun.net.httpserver.HttpExchange;
import java.util.Optional;

public record A2aServerPolicy(
    Optional<String> bearerToken,
    int requestsPerMinute
) {
    public static A2aServerPolicy fromSystemProperties() {
        Optional<String> bearerToken = Optional.ofNullable(System.getProperty("agentmesh.a2a.bearerToken"))
            .filter(value -> !value.isBlank());
        int requestsPerMinute = Integer.getInteger("agentmesh.a2a.requestsPerMinute", 120);
        return new A2aServerPolicy(bearerToken, requestsPerMinute);
    }

    public A2aServerPolicy {
        bearerToken = bearerToken == null ? Optional.empty() : bearerToken;
        requestsPerMinute = Math.max(1, requestsPerMinute);
    }

    public boolean isAuthorized(HttpExchange exchange) {
        if (bearerToken.isEmpty()) {
            return true;
        }
        String expected = "Bearer " + bearerToken.get();
        return exchange.getRequestHeaders().getOrDefault("Authorization", java.util.List.of()).stream()
            .anyMatch(expected::equals);
    }

    public boolean usesAuthentication() {
        return bearerToken.isPresent();
    }
}
