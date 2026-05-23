package com.agentmesh.deterministic.a2a;

import com.agentmesh.deterministic.agents.AgentId;
import com.agentmesh.deterministic.agents.RemoteAgentHosts;
import com.agentmesh.deterministic.mock.MockLiteLlmGateway;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class A2aProtocolConformanceTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private MockLiteLlmGateway gateway;
    private RemoteAgentHosts hosts;
    private final HttpClient httpClient = HttpClient.newHttpClient();

    @BeforeEach
    void setUp() {
        gateway = new MockLiteLlmGateway(0);
        gateway.start();
        hosts = RemoteAgentHosts.startAllOnRandomPorts(gateway.baseUrl() + "/v1");
    }

    @AfterEach
    void tearDown() {
        if (hosts != null) {
            hosts.close();
        }
        if (gateway != null) {
            gateway.stop();
        }
        System.clearProperty("agentmesh.a2a.bearerToken");
    }

    @Test
    void agentCardAdvertisesOnlyLatestA2aInterfaces() throws Exception {
        JsonNode card = clinicalCard();

        assertEquals("clinical_retriever", card.path("name").asText());
        assertTrue(card.path("supportedInterfaces").isArray());
        assertTrue(hasInterface(card, "JSONRPC", AgentMeshA2aCards.PROTOCOL_VERSION));
        assertTrue(hasInterface(card, "HTTP+JSON", AgentMeshA2aCards.PROTOCOL_VERSION));
        assertFalse(card.has("protocolVersion"));
        assertFalse(card.has("url"));
        assertFalse(card.has("preferredTransport"));
        assertFalse(card.has("additionalInterfaces"));
        assertFalse(card.path("capabilities").path("streaming").asBoolean());
        assertFalse(card.has("signatures"));
    }

    @Test
    void jsonRpcSendMessageUsesA2aVersionHeaderAndLatestDataParts() throws Exception {
        JsonNode card = clinicalCard();
        String endpoint = interfaceUrl(card, "JSONRPC");
        String requestBody = MAPPER.writeValueAsString(Map.of(
            "jsonrpc", "2.0",
            "id", "conformance-1",
            "method", "SendMessage",
            "params", Map.of(
                "message", Map.of(
                    "role", "ROLE_USER",
                    "messageId", "msg-1",
                    "contextId", "ctx-1",
                    "parts", List.of(Map.of("kind", "text", "text", "Can I take aspirin for pain?"))
                ),
                "configuration", Map.of("acceptedOutputModes", List.of("application/json")),
                "tenant", ""
            )
        ));

        HttpResponse<String> response = httpClient.send(
            HttpRequest.newBuilder(URI.create(endpoint))
                .header("Content-Type", "application/json")
                .header("A2A-Version", "1.0")
                .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                .build(),
            HttpResponse.BodyHandlers.ofString()
        );
        JsonNode body = MAPPER.readTree(response.body());

        assertEquals(200, response.statusCode(), response.body());
        assertEquals("conformance-1", body.path("id").asText());
        JsonNode part = body.path("result").path("task").path("artifacts").path(0).path("parts").path(0);
        assertTrue(part.has("data"), response.body());
        assertTrue(part.path("data").path("matchFound").asBoolean());
    }

    @Test
    void legacyJsonRpcMethodAliasReturnsA2aErrorEnvelope() throws Exception {
        JsonNode card = clinicalCard();
        String requestBody = MAPPER.writeValueAsString(Map.of(
            "jsonrpc", "2.0",
            "id", "bad-method",
            "method", "message/send",
            "params", Map.of()
        ));

        HttpResponse<String> response = httpClient.send(
            HttpRequest.newBuilder(URI.create(interfaceUrl(card, "JSONRPC")))
                .header("Content-Type", "application/json")
                .header("A2A-Version", "1.0")
                .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                .build(),
            HttpResponse.BodyHandlers.ofString()
        );
        JsonNode body = MAPPER.readTree(response.body());

        assertEquals(200, response.statusCode(), response.body());
        assertEquals(-32004, body.path("error").path("code").asInt());
        assertTrue(body.path("error").path("message").asText().toLowerCase().contains("not supported"), response.body());
    }

    @Test
    void unsupportedA2aVersionReturnsProblemJson() throws Exception {
        JsonNode card = clinicalCard();

        HttpResponse<String> response = httpClient.send(
            HttpRequest.newBuilder(URI.create(interfaceUrl(card, "JSONRPC")))
                .header("Content-Type", "application/json")
                .header("A2A-Version", "0.3")
                .POST(HttpRequest.BodyPublishers.ofString("{}"))
                .build(),
            HttpResponse.BodyHandlers.ofString()
        );
        JsonNode body = MAPPER.readTree(response.body());

        assertEquals(400, response.statusCode());
        assertEquals("Protocol Version Not Supported", body.path("title").asText());
        assertTrue(body.path("supportedVersions").toString().contains(AgentMeshA2aCards.PROTOCOL_VERSION));
    }

    @Test
    void bearerTokenPolicyIsAdvertisedAndEnforcedWhenConfigured() throws Exception {
        hosts.close();
        System.setProperty("agentmesh.a2a.bearerToken", "test-token");
        hosts = RemoteAgentHosts.startAllOnRandomPorts(gateway.baseUrl() + "/v1");

        JsonNode card = clinicalCard();
        assertTrue(card.path("securitySchemes").has("agentmeshBearer"));

        HttpResponse<String> response = httpClient.send(
            HttpRequest.newBuilder(URI.create(interfaceUrl(card, "JSONRPC")))
                .header("Content-Type", "application/json")
                .header("A2A-Version", "1.0")
                .POST(HttpRequest.BodyPublishers.ofString("{}"))
                .build(),
            HttpResponse.BodyHandlers.ofString()
        );

        assertEquals(401, response.statusCode());
        assertEquals("Bearer", response.headers().firstValue("WWW-Authenticate").orElse(""));
    }

    private JsonNode clinicalCard() throws Exception {
        String baseUrl = hosts.baseUrls().get(AgentId.CLINICAL_RETRIEVER);
        HttpResponse<String> response = httpClient.send(
            HttpRequest.newBuilder(URI.create(baseUrl + "/.well-known/agent-card.json")).GET().build(),
            HttpResponse.BodyHandlers.ofString()
        );
        assertEquals(200, response.statusCode());
        return MAPPER.readTree(response.body());
    }

    private boolean hasInterface(JsonNode card, String binding, String version) {
        for (JsonNode item : card.path("supportedInterfaces")) {
            if (binding.equals(item.path("protocolBinding").asText())
                && version.equals(item.path("protocolVersion").asText())) {
                return true;
            }
        }
        return false;
    }

    private String interfaceUrl(JsonNode card, String binding) {
        for (JsonNode item : card.path("supportedInterfaces")) {
            if (binding.equals(item.path("protocolBinding").asText())) {
                return item.path("url").asText();
            }
        }
        throw new IllegalArgumentException("No interface found for " + binding);
    }
}
