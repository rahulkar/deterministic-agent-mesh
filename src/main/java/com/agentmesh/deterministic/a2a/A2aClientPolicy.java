package com.agentmesh.deterministic.a2a;

import com.agentmesh.deterministic.schema.AgentPayloadException;
import java.net.URI;
import java.time.Duration;
import java.util.Arrays;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

public record A2aClientPolicy(
    Duration timeout,
    int maxRetries,
    boolean requireHttps,
    Set<String> trustedHosts,
    Optional<String> bearerToken,
    String preferredProtocolVersion
) {
    public static A2aClientPolicy fromSystemProperties() {
        Duration timeout = Duration.ofMillis(Long.getLong("agentmesh.a2a.timeoutMillis", 3_000L));
        int maxRetries = Integer.getInteger("agentmesh.a2a.maxRetries", 1);
        boolean requireHttps = Boolean.getBoolean("agentmesh.a2a.requireHttps");
        Set<String> trustedHosts = split(System.getProperty("agentmesh.a2a.trustedHosts", ""));
        Optional<String> bearerToken = Optional.ofNullable(System.getProperty("agentmesh.a2a.bearerToken"))
            .filter(value -> !value.isBlank());
        String preferredProtocolVersion = System.getProperty("agentmesh.a2a.protocolVersion", "1.0");
        return new A2aClientPolicy(timeout, maxRetries, requireHttps, trustedHosts, bearerToken, preferredProtocolVersion);
    }

    public A2aClientPolicy {
        if (timeout == null || timeout.isNegative() || timeout.isZero()) {
            timeout = Duration.ofSeconds(3);
        }
        maxRetries = Math.max(0, maxRetries);
        trustedHosts = Set.copyOf(trustedHosts == null ? Set.of() : trustedHosts);
        bearerToken = bearerToken == null ? Optional.empty() : bearerToken;
        if (preferredProtocolVersion == null || preferredProtocolVersion.isBlank()) {
            preferredProtocolVersion = "1.0";
        }
    }

    public void validateEndpoint(String agentName, URI endpoint) {
        if (requireHttps && !"https".equalsIgnoreCase(endpoint.getScheme())) {
            throw new AgentPayloadException(agentName + " endpoint is not HTTPS while HTTPS is required");
        }
        if (!trustedHosts.isEmpty() && !trustedHosts.contains(endpoint.getHost().toLowerCase(Locale.ROOT))) {
            throw new AgentPayloadException(agentName + " endpoint host is not trusted: " + endpoint.getHost());
        }
    }

    private static Set<String> split(String value) {
        if (value == null || value.isBlank()) {
            return Set.of();
        }
        return Arrays.stream(value.split(","))
            .map(String::trim)
            .filter(item -> !item.isEmpty())
            .map(item -> item.toLowerCase(Locale.ROOT))
            .collect(Collectors.toSet());
    }
}
