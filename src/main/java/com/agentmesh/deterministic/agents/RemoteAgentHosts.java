package com.agentmesh.deterministic.agents;

import com.agentmesh.deterministic.a2a.A2aServerPolicy;
import com.agentmesh.deterministic.a2a.AgentMeshA2aCards;
import com.agentmesh.deterministic.a2a.AgentMeshA2aRequestHandler;
import com.agentmesh.deterministic.a2a.TokenBucketRateLimiter;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.a2aproject.sdk.jsonrpc.common.json.JsonProcessingException;
import org.a2aproject.sdk.jsonrpc.common.json.JsonUtil;
import org.a2aproject.sdk.jsonrpc.common.wrappers.A2AErrorResponse;
import org.a2aproject.sdk.jsonrpc.common.wrappers.SendMessageRequest;
import org.a2aproject.sdk.server.AgentCardCacheMetadata;
import org.a2aproject.sdk.server.ServerCallContext;
import org.a2aproject.sdk.server.auth.UnauthenticatedUser;
import org.a2aproject.sdk.spec.A2AError;
import org.a2aproject.sdk.spec.AgentCard;
import org.a2aproject.sdk.spec.UnsupportedOperationError;
import org.a2aproject.sdk.transport.jsonrpc.handler.JSONRPCHandler;
import org.a2aproject.sdk.transport.rest.handler.RestHandler;

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
        private static final AtomicInteger THREAD_COUNTER = new AtomicInteger();

        private final AgentId agentId;
        private final String liteLlmBaseUrl;
        private final int requestedPort;
        private final A2aServerPolicy policy = A2aServerPolicy.fromSystemProperties();
        private final TokenBucketRateLimiter rateLimiter = new TokenBucketRateLimiter(policy.requestsPerMinute());
        private HttpServer server;
        private ExecutorService executorService;
        private int actualPort;
        private AgentCard card;
        private JSONRPCHandler jsonRpcHandler;
        private RestHandler restHandler;

        private RemoteAgentHost(AgentId agentId, String liteLlmBaseUrl, int requestedPort) {
            this.agentId = agentId;
            this.liteLlmBaseUrl = liteLlmBaseUrl;
            this.requestedPort = requestedPort;
        }

        private void start() {
            try {
                server = HttpServer.create(new InetSocketAddress(requestedPort), 0);
                actualPort = server.getAddress().getPort();
                card = AgentMeshA2aCards.publicCard(agentId, baseUrl(), policy);
                AgentMeshA2aRequestHandler requestHandler = new AgentMeshA2aRequestHandler(agentId, liteLlmBaseUrl);
                executorService = Executors.newFixedThreadPool(4, runnable -> {
                    Thread thread = new Thread(runnable, "a2a-sdk-host-" + agentId.wireName() + "-" + THREAD_COUNTER.incrementAndGet());
                    thread.setDaemon(true);
                    return thread;
                });
                jsonRpcHandler = new JSONRPCHandler(card, requestHandler, executorService);
                restHandler = new RestHandler(card, new AgentCardCacheMetadata(card, null), requestHandler, executorService);
                server.createContext("/.well-known/agent-card.json", this::handleAgentCard);
                server.createContext("/", this::handleJsonRpc);
                server.createContext("/message:send", this::handleRestMessageSend);
                server.setExecutor(executorService);
                server.start();
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
                send(exchange, 405, "application/json", "{\"error\":\"method_not_allowed\"}");
                return;
            }
            send(exchange, 200, "application/json", publicCardJson());
        }

        private void handleJsonRpc(HttpExchange exchange) throws IOException {
            if (!"/".equals(exchange.getRequestURI().getPath())) {
                send(exchange, 404, "application/json", "{\"error\":\"not_found\"}");
                return;
            }
            if (!"POST".equals(exchange.getRequestMethod())) {
                send(exchange, 405, "application/json", "{\"error\":\"method_not_allowed\"}");
                return;
            }
            if (!preflight(exchange)) {
                return;
            }
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            try {
                JsonObject requestObject = JsonUtil.OBJECT_MAPPER.fromJson(body, JsonObject.class);
                String method = requestObject.get("method").getAsString();
                if (!"SendMessage".equals(method)) {
                    Object id = jsonRpcId(requestObject);
                    send(exchange, 200, "application/json", toJson(new A2AErrorResponse(id, new UnsupportedOperationError())));
                    return;
                }
                SendMessageRequest request = JsonUtil.fromJson(body, SendMessageRequest.class);
                send(exchange, 200, "application/json", toJson(jsonRpcHandler.onMessageSend(request, context())));
            } catch (A2AError error) {
                send(exchange, 200, "application/json", toJson(new A2AErrorResponse(error)));
            } catch (Exception e) {
                send(exchange, 400, "application/json", "{\"error\":\"invalid_request\",\"message\":\"" + escape(e.getMessage()) + "\"}");
            }
        }

        private void handleRestMessageSend(HttpExchange exchange) throws IOException {
            if (!"POST".equals(exchange.getRequestMethod())) {
                send(exchange, 405, "application/json", "{\"error\":\"method_not_allowed\"}");
                return;
            }
            if (!preflight(exchange)) {
                return;
            }
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            RestHandler.HTTPRestResponse response = restHandler.sendMessage(context(), body, null);
            response.getHeaders().forEach((name, value) -> exchange.getResponseHeaders().set(name, value));
            send(exchange, response.getStatusCode(), response.getContentType(), response.getBody());
        }

        private boolean preflight(HttpExchange exchange) throws IOException {
            String remoteKey = exchange.getRemoteAddress().getAddress().getHostAddress();
            if (!rateLimiter.tryAcquire(remoteKey)) {
                send(exchange, 429, "application/json", "{\"error\":\"rate_limit_exceeded\"}");
                return false;
            }
            if (!policy.isAuthorized(exchange)) {
                exchange.getResponseHeaders().set("WWW-Authenticate", "Bearer");
                send(exchange, 401, "application/json", "{\"error\":\"authentication_required\"}");
                return false;
            }
            String version = exchange.getRequestHeaders().getFirst("A2A-Version");
            if (version != null && !version.isBlank() && !AgentMeshA2aCards.PROTOCOL_VERSION.equals(version)) {
                send(exchange, 400, "application/json", "{\"error\":\"version_not_supported\",\"title\":\"Protocol Version Not Supported\",\"supportedVersions\":[\"" + AgentMeshA2aCards.PROTOCOL_VERSION + "\"]}");
                return false;
            }
            return true;
        }

        private ServerCallContext context() {
            return new ServerCallContext(UnauthenticatedUser.INSTANCE, Map.of(), Set.of(), AgentMeshA2aCards.PROTOCOL_VERSION);
        }

        private String toJson(Object value) throws IOException {
            try {
                return JsonUtil.toJson(value);
            } catch (JsonProcessingException e) {
                throw new IOException(e);
            }
        }

        private String publicCardJson() throws IOException {
            JsonObject cardObject = JsonUtil.OBJECT_MAPPER.fromJson(toJson(card), JsonObject.class);
            cardObject.remove("protocolVersion");
            cardObject.remove("url");
            cardObject.remove("preferredTransport");
            cardObject.remove("additionalInterfaces");
            return JsonUtil.OBJECT_MAPPER.toJson(cardObject);
        }

        private Object jsonRpcId(JsonObject requestObject) {
            JsonElement id = requestObject.get("id");
            if (id == null || id.isJsonNull()) {
                return null;
            }
            if (id.isJsonPrimitive()) {
                if (id.getAsJsonPrimitive().isNumber()) {
                    return id.getAsNumber();
                }
                if (id.getAsJsonPrimitive().isBoolean()) {
                    return id.getAsBoolean();
                }
                return id.getAsString();
            }
            return id.toString();
        }

        private void send(HttpExchange exchange, int status, String contentType, String body) throws IOException {
            byte[] bytes = (body == null ? "" : body).getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", contentType == null ? "application/json" : contentType);
            exchange.sendResponseHeaders(status, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(bytes);
            }
        }

        private String escape(String value) {
            return value == null ? "" : value.replace("\\", "\\\\").replace("\"", "\\\"");
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
    }
}
