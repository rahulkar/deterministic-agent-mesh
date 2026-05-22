package com.agentmesh.deterministic.a2a;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.agentmesh.deterministic.agents.AgentId;
import com.agentmesh.deterministic.schema.AgentPayloadException;
import com.agentmesh.deterministic.schema.AgentPayloadValidator;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

public class A2aRemoteAgentClient {
    private static final AtomicInteger THREAD_COUNTER = new AtomicInteger();

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final Map<AgentId, String> agentBaseUrls;
    private final Map<AgentId, JsonNode> agentCards = new ConcurrentHashMap<>();
    private final A2aClientPolicy policy;
    private final AgentCircuitBreaker circuitBreaker;

    public A2aRemoteAgentClient() {
        this(defaultBaseUrls());
    }

    public A2aRemoteAgentClient(Map<AgentId, String> agentBaseUrls) {
        this(agentBaseUrls, A2aClientPolicy.fromSystemProperties());
    }

    public A2aRemoteAgentClient(Map<AgentId, String> agentBaseUrls, A2aClientPolicy policy) {
        this.policy = policy;
        this.circuitBreaker = new AgentCircuitBreaker();
        this.httpClient = HttpClient.newBuilder().connectTimeout(policy.timeout()).executor(daemonExecutor()).build();
        this.objectMapper = new ObjectMapper();
        this.agentBaseUrls = new EnumMap<>(agentBaseUrls);
    }

    public <T> T invoke(AgentId agentId, String prompt, String correlationId, Class<T> payloadType) {
        circuitBreaker.beforeCall(agentId);
        try {
            JsonNode card = resolveAgentCard(agentId);
            Endpoint endpoint = selectEndpoint(agentId, card);
            policy.validateEndpoint(agentId.wireName(), endpoint.uri());

            T payload = executeWithRetry(agentId, prompt, correlationId, payloadType, endpoint);
            circuitBreaker.recordSuccess(agentId);
            return payload;
        } catch (AgentPayloadException e) {
            circuitBreaker.recordFailure(agentId);
            throw e;
        }
    }

    private <T> T executeWithRetry(
        AgentId agentId,
        String prompt,
        String correlationId,
        Class<T> payloadType,
        Endpoint endpoint
    ) {
        AgentPayloadException lastFailure = null;
        for (int attempt = 0; attempt <= policy.maxRetries(); attempt++) {
            try {
                return send(agentId, prompt, correlationId, payloadType, endpoint);
            } catch (AgentPayloadException e) {
                lastFailure = e;
                if (!isRetryable(e) || attempt == policy.maxRetries()) {
                    throw e;
                }
            }
        }
        throw lastFailure == null ? new AgentPayloadException(agentId.wireName() + " call failed") : lastFailure;
    }

    private <T> T send(AgentId agentId, String prompt, String correlationId, Class<T> payloadType, Endpoint endpoint) {
        try {
            String requestBody = endpoint.isRest()
                ? objectMapper.writeValueAsString(Map.of(
                    "message", Map.of(
                        "messageId", correlationId,
                        "role", "ROLE_USER",
                        "parts", java.util.List.of(Map.of("text", prompt))
                    )
                ))
                : objectMapper.writeValueAsString(Map.of(
                    "jsonrpc", "2.0",
                    "id", correlationId + ":" + agentId.wireName(),
                    "method", "SendMessage",
                    "params", Map.of(
                        "message", Map.of(
                            "messageId", correlationId,
                            "role", "ROLE_USER",
                            "parts", java.util.List.of(Map.of("text", prompt))
                        )
                    )
                ));

            HttpRequest.Builder builder = HttpRequest.newBuilder(endpoint.uri())
                .timeout(policy.timeout())
                .header("Content-Type", endpoint.isRest() ? "application/a2a+json" : "application/json")
                .header("A2A-Version", endpoint.protocolVersion())
                .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                .version(HttpClient.Version.HTTP_1_1);
            policy.bearerToken().ifPresent(token -> builder.header("Authorization", "Bearer " + token));

            HttpResponse<String> response = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 400) {
                throw new AgentPayloadException(agentId.wireName() + " returned HTTP " + response.statusCode() + ": " + summarizeError(response.body()));
            }
            JsonNode root = objectMapper.readTree(response.body());
            if (root.hasNonNull("error")) {
                throw new AgentPayloadException(agentId.wireName() + " returned A2A error: " + summarizeError(root.path("error").toString()));
            }
            JsonNode payloadNode = payloadNode(root);
            if (payloadNode.isMissingNode() || payloadNode.isNull()) {
                throw new AgentPayloadException(agentId.wireName() + " returned no payload");
            }
            T payload = objectMapper.treeToValue(payloadNode, payloadType);
            AgentPayloadValidator.validate(agentId.wireName(), payload);
            return payload;
        } catch (AgentPayloadException e) {
            throw e;
        } catch (IOException e) {
            throw new AgentPayloadException(agentId.wireName() + " failed payload parsing", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AgentPayloadException(agentId.wireName() + " call interrupted", e);
        }
    }

    public JsonNode resolveAgentCard(AgentId agentId) {
        return agentCards.computeIfAbsent(agentId, this::fetchAgentCard);
    }

    private JsonNode fetchAgentCard(AgentId agentId) {
        String baseUrl = agentBaseUrls.get(agentId);
        if (baseUrl == null || baseUrl.isBlank()) {
            throw new AgentPayloadException("No A2A endpoint configured for " + agentId.wireName());
        }
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + "/.well-known/agent-card.json"))
                .timeout(policy.timeout())
                .GET()
                .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 400) {
                throw new AgentPayloadException(agentId.wireName() + " Agent Card HTTP " + response.statusCode());
            }
            JsonNode card = objectMapper.readTree(response.body());
            if (!agentId.wireName().equals(card.path("name").asText())) {
                throw new AgentPayloadException("Agent Card identity mismatch for " + agentId.wireName());
            }
            selectEndpoint(agentId, card);
            return card;
        } catch (IOException e) {
            throw new AgentPayloadException(agentId.wireName() + " Agent Card parse failure", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AgentPayloadException(agentId.wireName() + " Agent Card fetch interrupted", e);
        }
    }

    private Endpoint selectEndpoint(AgentId agentId, JsonNode card) {
        JsonNode interfaces = card.path("supportedInterfaces");
        if (interfaces.isArray()) {
            Endpoint jsonRpc = null;
            Endpoint fallback = null;
            for (JsonNode item : interfaces) {
                String binding = item.path("protocolBinding").asText();
                String version = item.path("protocolVersion").asText(policy.preferredProtocolVersion());
                String url = item.path("url").asText();
                if (url.isBlank()) {
                    continue;
                }
                Endpoint endpoint = new Endpoint(URI.create(url), binding, version);
                if (fallback == null) {
                    fallback = endpoint;
                }
                if ("JSONRPC".equalsIgnoreCase(binding)) {
                    jsonRpc = endpoint;
                    if (policy.preferredProtocolVersion().equals(version)) {
                        return endpoint;
                    }
                }
            }
            if (jsonRpc != null) {
                return jsonRpc;
            }
            if (fallback != null) {
                return fallback;
            }
        }

        JsonNode additionalInterfaces = card.path("additionalInterfaces");
        if (additionalInterfaces.isArray() && !additionalInterfaces.isEmpty()) {
            JsonNode item = additionalInterfaces.get(0);
            return new Endpoint(
                URI.create(item.path("url").asText()),
                item.path("transport").asText("JSONRPC"),
                card.path("protocolVersion").asText("0.3")
            );
        }

        String legacyEndpoint = card.path("url").asText();
        if (!legacyEndpoint.isBlank()) {
            return new Endpoint(
                URI.create(legacyEndpoint),
                card.path("preferredTransport").asText("JSONRPC"),
                card.path("protocolVersion").asText("0.3")
            );
        }
        throw new AgentPayloadException(agentId.wireName() + " Agent Card has no supported A2A interface");
    }

    private JsonNode payloadNode(JsonNode root) {
        JsonNode payload = root.path("result").path("payload");
        if (!payload.isMissingNode() && !payload.isNull()) {
            return payload;
        }
        payload = root.path("result").path("message").path("parts").path(0).path("data");
        if (!payload.isMissingNode() && !payload.isNull()) {
            return payload;
        }
        return root.path("message").path("parts").path(0).path("data");
    }

    private boolean isRetryable(AgentPayloadException e) {
        String message = e.getMessage();
        return message != null && (message.contains("interrupted") || message.contains("HTTP 5"));
    }

    private String summarizeError(String body) {
        if (body == null || body.isBlank()) {
            return "empty error body";
        }
        String compact = body.replaceAll("\\s+", " ").trim();
        return compact.length() <= 240 ? compact : compact.substring(0, 240) + "...";
    }

    private static Map<AgentId, String> defaultBaseUrls() {
        EnumMap<AgentId, String> urls = new EnumMap<>(AgentId.class);
        for (AgentId agentId : AgentId.values()) {
            urls.put(agentId, agentId.baseUrl());
        }
        return urls;
    }

    private static ExecutorService daemonExecutor() {
        return Executors.newCachedThreadPool(runnable -> {
            Thread thread = new Thread(runnable, "a2a-client-" + THREAD_COUNTER.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        });
    }

    private record Endpoint(URI uri, String binding, String protocolVersion) {
        boolean isRest() {
            return "HTTP+JSON".equalsIgnoreCase(binding);
        }
    }
}
