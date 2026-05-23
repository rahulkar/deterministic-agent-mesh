package com.agentmesh.deterministic.a2a;

import com.agentmesh.deterministic.agents.AgentId;
import com.agentmesh.deterministic.schema.AgentPayloadException;
import com.agentmesh.deterministic.schema.AgentPayloadValidator;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.EnumMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import org.a2aproject.sdk.client.Client;
import org.a2aproject.sdk.client.ClientEvent;
import org.a2aproject.sdk.client.MessageEvent;
import org.a2aproject.sdk.client.TaskEvent;
import org.a2aproject.sdk.client.TaskUpdateEvent;
import org.a2aproject.sdk.client.config.ClientConfig;
import org.a2aproject.sdk.client.transport.jsonrpc.JSONRPCTransport;
import org.a2aproject.sdk.client.transport.jsonrpc.JSONRPCTransportConfig;
import org.a2aproject.sdk.jsonrpc.common.json.JsonProcessingException;
import org.a2aproject.sdk.jsonrpc.common.json.JsonUtil;
import org.a2aproject.sdk.spec.AgentCard;
import org.a2aproject.sdk.spec.AgentInterface;
import org.a2aproject.sdk.spec.DataPart;
import org.a2aproject.sdk.spec.EventKind;
import org.a2aproject.sdk.spec.Message;
import org.a2aproject.sdk.spec.MessageSendConfiguration;
import org.a2aproject.sdk.spec.MessageSendParams;
import org.a2aproject.sdk.spec.Part;
import org.a2aproject.sdk.spec.Task;
import org.a2aproject.sdk.spec.TextPart;
import org.a2aproject.sdk.spec.TransportProtocol;

public class A2aRemoteAgentClient {
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final Map<AgentId, String> agentBaseUrls;
    private final Map<AgentId, AgentCard> agentCards = new ConcurrentHashMap<>();
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
        this.httpClient = HttpClient.newBuilder().connectTimeout(policy.timeout()).build();
        this.objectMapper = new ObjectMapper();
        this.agentBaseUrls = new EnumMap<>(agentBaseUrls);
    }

    public <T> T invoke(AgentId agentId, String prompt, String correlationId, Class<T> payloadType) {
        circuitBreaker.beforeCall(agentId);
        try {
            AgentCard card = resolveAgentCard(agentId);
            validateLatestInterfaces(agentId, card);
            EventKind event = executeWithRetry(agentId, prompt, correlationId, card);
            T payload = objectMapper.treeToValue(payloadNode(agentId, event), payloadType);
            AgentPayloadValidator.validate(agentId.wireName(), payload);
            circuitBreaker.recordSuccess(agentId);
            return payload;
        } catch (AgentPayloadException e) {
            circuitBreaker.recordFailure(agentId);
            throw e;
        } catch (IOException e) {
            circuitBreaker.recordFailure(agentId);
            throw new AgentPayloadException(agentId.wireName() + " failed payload parsing", e);
        }
    }

    private EventKind executeWithRetry(AgentId agentId, String prompt, String correlationId, AgentCard card) {
        AgentPayloadException lastFailure = null;
        for (int attempt = 0; attempt <= policy.maxRetries(); attempt++) {
            try {
                return send(agentId, prompt, correlationId, card);
            } catch (AgentPayloadException e) {
                lastFailure = e;
                if (!isRetryable(e) || attempt == policy.maxRetries()) {
                    throw e;
                }
            }
        }
        throw lastFailure == null ? new AgentPayloadException(agentId.wireName() + " call failed") : lastFailure;
    }

    private EventKind send(AgentId agentId, String prompt, String correlationId, AgentCard card) {
        Client client = null;
        try {
            Message message = Message.builder()
                .messageId(correlationId)
                .contextId(correlationId)
                .role(Message.Role.ROLE_USER)
                .parts(List.of(new TextPart(prompt == null ? "" : prompt)))
                .build();
            MessageSendParams params = MessageSendParams.builder()
                .message(message)
                .configuration(MessageSendConfiguration.builder()
                    .acceptedOutputModes(List.of("application/json"))
                    .build())
                .build();
            List<ClientEvent> events = new ArrayList<>();
            AtomicReference<Throwable> asyncError = new AtomicReference<>();
            client = Client.builder(card)
                .withTransport(JSONRPCTransport.class, new JSONRPCTransportConfig())
                .clientConfig(ClientConfig.builder()
                    .setAcceptedOutputModes(List.of("application/json"))
                    .setUseClientPreference(true)
                    .build())
                .build();
            client.sendMessage(
                params,
                List.of((event, ignoredCard) -> events.add(event)),
                asyncError::set,
                null
            );
            if (asyncError.get() != null) {
                throw new AgentPayloadException(agentId.wireName() + " A2A async client error: " + asyncError.get().getMessage(), asyncError.get());
            }
            return eventKind(agentId, events);
        } catch (Exception e) {
            throw new AgentPayloadException(agentId.wireName() + " A2A call failed: " + e.getMessage(), e);
        } finally {
            if (client != null) {
                client.close();
            }
        }
    }

    private EventKind eventKind(AgentId agentId, List<ClientEvent> events) {
        for (int i = events.size() - 1; i >= 0; i--) {
            ClientEvent event = events.get(i);
            if (event instanceof TaskEvent taskEvent) {
                return taskEvent.getTask();
            }
            if (event instanceof TaskUpdateEvent taskUpdateEvent) {
                return taskUpdateEvent.getTask();
            }
            if (event instanceof MessageEvent messageEvent) {
                return messageEvent.getMessage();
            }
        }
        throw new AgentPayloadException(agentId.wireName() + " returned no A2A client event");
    }

    public AgentCard resolveAgentCard(AgentId agentId) {
        return agentCards.computeIfAbsent(agentId, this::fetchAgentCard);
    }

    private AgentCard fetchAgentCard(AgentId agentId) {
        String baseUrl = agentBaseUrls.get(agentId);
        if (baseUrl == null || baseUrl.isBlank()) {
            throw new AgentPayloadException("No A2A endpoint configured for " + agentId.wireName());
        }
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(stripSlash(baseUrl) + "/.well-known/agent-card.json"))
                .timeout(policy.timeout())
                .GET()
                .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 400) {
                throw new AgentPayloadException(agentId.wireName() + " Agent Card HTTP " + response.statusCode());
            }
            AgentCard card = JsonUtil.fromJson(response.body(), AgentCard.class);
            if (!agentId.wireName().equals(card.name())) {
                throw new AgentPayloadException("Agent Card identity mismatch for " + agentId.wireName());
            }
            validateLatestInterfaces(agentId, card);
            return card;
        } catch (JsonProcessingException | IOException e) {
            throw new AgentPayloadException(agentId.wireName() + " Agent Card parse failure", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AgentPayloadException(agentId.wireName() + " Agent Card fetch interrupted", e);
        }
    }

    private void validateLatestInterfaces(AgentId agentId, AgentCard card) {
        if (card.supportedInterfaces() == null || card.supportedInterfaces().isEmpty()) {
            throw new AgentPayloadException(agentId.wireName() + " Agent Card has no supportedInterfaces");
        }
        for (AgentInterface item : card.supportedInterfaces()) {
            if (item.url() == null || item.url().isBlank()) {
                throw new AgentPayloadException(agentId.wireName() + " Agent Card has a blank interface URL");
            }
            if (!AgentMeshA2aCards.PROTOCOL_VERSION.equals(item.protocolVersion())) {
                throw new AgentPayloadException(agentId.wireName() + " does not support required A2A version " + AgentMeshA2aCards.PROTOCOL_VERSION);
            }
            policy.validateEndpoint(agentId.wireName(), URI.create(item.url()));
        }
        boolean hasJsonRpc = card.supportedInterfaces().stream()
            .anyMatch(item -> TransportProtocol.JSONRPC.asString().equals(item.protocolBinding()));
        if (!hasJsonRpc) {
            throw new AgentPayloadException(agentId.wireName() + " Agent Card has no JSON-RPC interface");
        }
    }

    private JsonNode payloadNode(AgentId agentId, EventKind event) {
        if (event instanceof Task task) {
            for (org.a2aproject.sdk.spec.Artifact artifact : nullSafe(task.artifacts())) {
                JsonNode payload = firstDataPart(artifact.parts());
                if (payload != null) {
                    return payload;
                }
            }
            if (task.status() != null && task.status().message() != null) {
                JsonNode payload = firstDataPart(task.status().message().parts());
                if (payload != null) {
                    return payload;
                }
            }
        }
        if (event instanceof Message message) {
            JsonNode payload = firstDataPart(message.parts());
            if (payload != null) {
                return payload;
            }
        }
        throw new AgentPayloadException(agentId.wireName() + " returned no payload");
    }

    private JsonNode firstDataPart(List<Part<?>> parts) {
        for (Part<?> part : nullSafe(parts)) {
            if (part instanceof DataPart dataPart) {
                return objectMapper.valueToTree(dataPart.data());
            }
        }
        return null;
    }

    private <T> List<T> nullSafe(List<T> values) {
        return values == null ? List.of() : values;
    }

    private boolean isRetryable(AgentPayloadException e) {
        String message = e.getMessage();
        return message != null && (message.contains("interrupted") || message.contains("HTTP 5") || message.contains("A2A call failed"));
    }

    private static Map<AgentId, String> defaultBaseUrls() {
        EnumMap<AgentId, String> urls = new EnumMap<>(AgentId.class);
        for (AgentId agentId : AgentId.values()) {
            urls.put(agentId, agentId.baseUrl());
        }
        return urls;
    }

    private static String stripSlash(String baseUrl) {
        return baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
    }
}
