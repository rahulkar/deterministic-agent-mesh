package com.agentmesh.deterministic.agents;

import com.agentmesh.deterministic.a2a.A2aServerPolicy;
import com.agentmesh.deterministic.a2a.TokenBucketRateLimiter;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

public class RemoteAgentHosts implements AutoCloseable {
    private final List<RemoteAgentHost> hosts;

    private RemoteAgentHosts(List<RemoteAgentHost> hosts) {
        this.hosts = hosts;
    }

    public static RemoteAgentHosts startAll(String liteLlmBaseUrl) {
        EnumMap<AgentId, Integer> ports = new EnumMap<>(AgentId.class);
        for (AgentId agentId : AgentId.values()) {
            ports.put(agentId, agentId.port());
        }
        return startAll(liteLlmBaseUrl, ports);
    }

    public static RemoteAgentHosts startAllOnRandomPorts(String liteLlmBaseUrl) {
        EnumMap<AgentId, Integer> ports = new EnumMap<>(AgentId.class);
        for (AgentId agentId : AgentId.values()) {
            ports.put(agentId, 0);
        }
        return startAll(liteLlmBaseUrl, ports);
    }

    public static RemoteAgentHosts startAll(String liteLlmBaseUrl, Map<AgentId, Integer> ports) {
        List<RemoteAgentHost> started = new ArrayList<>();
        for (AgentId agentId : AgentId.values()) {
            RemoteAgentHost host = new RemoteAgentHost(agentId, liteLlmBaseUrl, ports.getOrDefault(agentId, agentId.port()));
            host.start();
            started.add(host);
        }
        return new RemoteAgentHosts(started);
    }

    public Map<AgentId, String> baseUrls() {
        EnumMap<AgentId, String> urls = new EnumMap<>(AgentId.class);
        for (RemoteAgentHost host : hosts) {
            urls.put(host.agentId, host.baseUrl());
        }
        return urls;
    }

    @Override
    public void close() {
        for (RemoteAgentHost host : hosts.reversed()) {
            host.close();
        }
    }

    private static final class RemoteAgentHost implements AutoCloseable {
        private static final ObjectMapper MAPPER = new ObjectMapper();
        private static final Duration TIMEOUT = Duration.ofSeconds(3);
        private static final AtomicInteger THREAD_COUNTER = new AtomicInteger();

        private final AgentId agentId;
        private final String liteLlmBaseUrl;
        private final int requestedPort;
        private final HttpClient httpClient = HttpClient.newBuilder().connectTimeout(TIMEOUT).executor(daemonExecutor()).build();
        private final A2aServerPolicy policy = A2aServerPolicy.fromSystemProperties();
        private final TokenBucketRateLimiter rateLimiter = new TokenBucketRateLimiter(policy.requestsPerMinute());
        private HttpServer server;
        private ExecutorService executorService;
        private int actualPort;

        private RemoteAgentHost(AgentId agentId, String liteLlmBaseUrl, int requestedPort) {
            this.agentId = agentId;
            this.liteLlmBaseUrl = liteLlmBaseUrl;
            this.requestedPort = requestedPort;
        }

        private void start() {
            try {
                server = HttpServer.create(new InetSocketAddress(requestedPort), 0);
                server.createContext("/.well-known/agent-card.json", this::handleAgentCard);
                server.createContext("/a2a/remote/v1/jsonrpc", this::handleJsonRpcMessage);
                server.createContext("/a2a/remote/v1/message:send", this::handleMessageSend);
                executorService = Executors.newFixedThreadPool(2, runnable -> {
                    Thread thread = new Thread(runnable, "a2a-host-" + agentId.wireName() + "-" + THREAD_COUNTER.incrementAndGet());
                    thread.setDaemon(true);
                    return thread;
                });
                server.setExecutor(executorService);
                server.start();
                actualPort = server.getAddress().getPort();
                System.out.println("[A2A] " + agentId.wireName() + " listening at " + baseUrl());
            } catch (IOException e) {
                throw new IllegalStateException("Unable to start " + agentId.wireName(), e);
            }
        }

        private String baseUrl() {
            return "http://localhost:" + actualPort;
        }

        private void handleAgentCard(HttpExchange exchange) throws IOException {
            if (!"GET".equals(exchange.getRequestMethod())) {
                send(exchange, 405, "{\"error\":\"method_not_allowed\"}");
                return;
            }
            send(exchange, 200, MAPPER.writeValueAsString(agentCard()));
        }

        private Map<String, Object> agentCard() {
            Map<String, Object> card = new LinkedHashMap<>();
            card.put("name", agentId.wireName());
            card.put("description", agentId.description());
            card.put("supportedInterfaces", List.of(
                Map.of("url", baseUrl() + "/a2a/remote/v1/jsonrpc", "protocolBinding", "JSONRPC", "protocolVersion", "1.0"),
                Map.of("url", baseUrl() + "/a2a/remote/v1/message:send", "protocolBinding", "HTTP+JSON", "protocolVersion", "1.0")
            ));
            card.put("provider", Map.of("organization", "Deterministic Agent Mesh Demo", "url", "http://localhost"));
            card.put("version", "1.0.0");
            card.put("documentationUrl", "https://a2a-protocol.org/latest/specification/");
            card.put("capabilities", Map.of(
                "streaming", false,
                "pushNotifications", false,
                "stateTransitionHistory", false,
                "extendedAgentCard", false
            ));
            card.put("defaultInputModes", List.of("text/plain"));
            card.put("defaultOutputModes", List.of("application/json"));
            card.put("skills", List.of(Map.of(
                "id", agentId.wireName(),
                "name", agentId.wireName(),
                "description", agentId.description(),
                "tags", List.of("pharma", "deterministic-demo", "wiremock-backed"),
                "examples", List.of("Aspirin safety triage", "Prompt injection short-circuit validation"),
                "inputModes", List.of("text/plain"),
                "outputModes", List.of("application/json")
            )));
            if (policy.usesAuthentication()) {
                card.put("securitySchemes", Map.of(
                    "agentmeshBearer",
                    Map.of("httpAuthSecurityScheme", Map.of(
                        "scheme", "bearer",
                        "bearerFormat", "opaque",
                        "description", "Bearer token required for A2A message endpoints"
                    ))
                ));
                card.put("security", List.of(Map.of("agentmeshBearer", List.of())));
            }
            card.put("signatures", List.of());

            // Legacy fields keep older ADK/A2A 0.3 clients from breaking while the primary
            // discovery path above advertises the A2A 1.0 supportedInterfaces shape.
            card.put("url", baseUrl() + "/a2a/remote/v1/jsonrpc");
            card.put("preferredTransport", "JSONRPC");
            card.put("protocolVersion", "1.0");
            card.put("additionalInterfaces", List.of(
                Map.of("url", baseUrl() + "/a2a/remote/v1/jsonrpc", "transport", "JSONRPC"),
                Map.of("url", baseUrl() + "/a2a/remote/v1/message:send", "transport", "HTTP+JSON")
            ));
            return card;
        }

        private void handleJsonRpcMessage(HttpExchange exchange) throws IOException {
            if (!"POST".equals(exchange.getRequestMethod())) {
                send(exchange, 405, "{\"error\":\"method_not_allowed\"}");
                return;
            }
            if (!preflight(exchange)) {
                return;
            }
            try {
                JsonNode request = MAPPER.readTree(exchange.getRequestBody());
                String id = request.path("id").asText();
                String method = request.path("method").asText();
                if (!"SendMessage".equals(method) && !"message/send".equals(method)) {
                    send(exchange, 200, MAPPER.writeValueAsString(Map.of(
                        "jsonrpc", "2.0",
                        "id", id,
                        "error", Map.of(
                            "code", -32601,
                            "message", "UnsupportedOperationError: method is not supported by this agent"
                        )
                    )));
                    return;
                }
                String prompt = extractPrompt(request.path("params").path("message"));
                String payload = callLiteLlm(prompt);
                JsonNode payloadNode = MAPPER.readTree(payload);
                send(exchange, 200, MAPPER.writeValueAsString(Map.of(
                    "jsonrpc", "2.0",
                    "id", id,
                    "result", Map.of(
                        "agent", agentId.wireName(),
                        "message", a2aDataMessage(payloadNode, request.path("params").path("message").path("messageId").asText()),
                        "payload", payloadNode
                    )
                )));
            } catch (Exception e) {
                send(exchange, 500, MAPPER.writeValueAsString(Map.of(
                    "jsonrpc", "2.0",
                    "error", Map.of("code", -32000, "message", e.getMessage())
                )));
            }
        }

        private void handleMessageSend(HttpExchange exchange) throws IOException {
            if (!"POST".equals(exchange.getRequestMethod())) {
                send(exchange, 405, "{\"error\":\"method_not_allowed\"}");
                return;
            }
            if (!preflight(exchange)) {
                return;
            }
            try {
                JsonNode request = MAPPER.readTree(exchange.getRequestBody());
                String prompt = extractPrompt(request.path("message"));
                String messageId = request.path("message").path("messageId").asText();
                JsonNode payloadNode = MAPPER.readTree(callLiteLlm(prompt));
                send(exchange, 200, MAPPER.writeValueAsString(Map.of(
                    "message", a2aDataMessage(payloadNode, messageId)
                )));
            } catch (Exception e) {
                send(exchange, 500, MAPPER.writeValueAsString(Map.of(
                    "type", "https://a2a-protocol.org/errors/internal-error",
                    "title", "Internal Error",
                    "status", 500,
                    "detail", e.getMessage()
                )));
            }
        }

        private boolean preflight(HttpExchange exchange) throws IOException {
            String remoteKey = exchange.getRemoteAddress().getAddress().getHostAddress();
            if (!rateLimiter.tryAcquire(remoteKey)) {
                send(exchange, 429, MAPPER.writeValueAsString(Map.of(
                    "type", "https://a2a-protocol.org/errors/rate-limit",
                    "title", "Rate Limit Exceeded",
                    "status", 429,
                    "detail", "Too many A2A requests"
                )));
                return false;
            }
            if (!policy.isAuthorized(exchange)) {
                exchange.getResponseHeaders().set("WWW-Authenticate", "Bearer");
                send(exchange, 401, MAPPER.writeValueAsString(Map.of(
                    "type", "https://a2a-protocol.org/errors/authentication-required",
                    "title", "Authentication Required",
                    "status", 401,
                    "detail", "Bearer token is required for this A2A endpoint"
                )));
                return false;
            }
            String version = exchange.getRequestHeaders().getFirst("A2A-Version");
            if (version != null && !version.isBlank() && !"1.0".equals(version) && !"0.3".equals(version)) {
                send(exchange, 400, MAPPER.writeValueAsString(Map.of(
                    "type", "https://a2a-protocol.org/errors/version-not-supported",
                    "title", "Protocol Version Not Supported",
                    "status", 400,
                    "detail", "The requested A2A protocol version " + version + " is not supported by this agent",
                    "supportedVersions", List.of("1.0", "0.3")
                )));
                return false;
            }
            return true;
        }

        private String extractPrompt(JsonNode message) {
            JsonNode parts = message.path("parts");
            if (parts.isArray()) {
                for (JsonNode part : parts) {
                    if (part.hasNonNull("text")) {
                        return part.path("text").asText();
                    }
                }
            }
            return "";
        }

        private Map<String, Object> a2aDataMessage(JsonNode payloadNode, String requestMessageId) {
            String suffix = requestMessageId == null || requestMessageId.isBlank()
                ? String.valueOf(System.nanoTime())
                : requestMessageId;
            return Map.of(
                "role", "ROLE_AGENT",
                "messageId", agentId.wireName() + ":" + suffix,
                "parts", List.of(Map.of(
                    "data", payloadNode,
                    "mediaType", "application/json"
                ))
            );
        }

        private String callLiteLlm(String prompt) throws IOException, InterruptedException {
            String requestBody = MAPPER.writeValueAsString(Map.of(
                "model", "litellm/mock",
                "messages", List.of(
                    Map.of("role", "system", "content", "agent=" + agentId.wireName() + "; return exact JSON only"),
                    Map.of("role", "user", "content", prompt)
                )
            ));
            HttpRequest request = HttpRequest.newBuilder(URI.create(liteLlmBaseUrl + "/chat/completions"))
                .timeout(TIMEOUT)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 400) {
                throw new IOException("LiteLLM mock returned HTTP " + response.statusCode());
            }
            return MAPPER.readTree(response.body()).path("choices").path(0).path("message").path("content").asText();
        }

        private void send(HttpExchange exchange, int status, String body) throws IOException {
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(bytes);
            }
        }

        @Override
        public void close() {
            if (server != null) {
                server.stop(0);
            }
            if (executorService != null) {
                executorService.shutdownNow();
            }
        }

        private static ExecutorService daemonExecutor() {
            return Executors.newCachedThreadPool(runnable -> {
                Thread thread = new Thread(runnable, "a2a-host-client-" + THREAD_COUNTER.incrementAndGet());
                thread.setDaemon(true);
                return thread;
            });
        }
    }
}
