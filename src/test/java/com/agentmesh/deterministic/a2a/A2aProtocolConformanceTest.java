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
    void agentCardAdvertisesA2aOneInterfacesAndLegacyCompatibilityFields() throws Exception {
        JsonNode card = clinicalCard();

        assertEquals("clinical_retriever", card.path("name").asText());
        assertTrue(card.path("supportedInterfaces").isArray());
        assertTrue(hasInterface(card, "JSONRPC", "1.0"));
        assertTrue(hasInterface(card, "HTTP+JSON", "1.0"));
        assertEquals("1.0", card.path("protocolVersion").asText());
        assertEquals("JSONRPC", card.path("preferredTransport").asText());
        assertFalse(card.path("capabilities").path("streaming").asBoolean());
        assertTrue(card.path("signatures").isArray());
    }

    @Test
    void jsonRpcSendMessageUsesA2aVersionHeaderAndOnePointZeroDataParts() throws Exception {
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
                    "parts", List.of(Map.of("text", "Can I take aspirin for pain?"))
                )
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

        assertEquals(200, response.statusCode());
        assertEquals("conformance-1", body.path("id").asText());
        assertTrue(body.path("result").path("message").path("parts").path(0).has("data"));
        assertFalse(body.path("result").path("message").path("parts").path(0).has("kind"));
        assertTrue(body.path("result").path("message").path("parts").path(0).path("data").path("matchFound").asBoolean());
    }

    @Test
    void unsupportedJsonRpcMethodReturnsA2aErrorEnvelope() throws Exception {
        JsonNode card = clinicalCard();
        String requestBody = MAPPER.writeValueAsString(Map.of(
            "jsonrpc", "2.0",
            "id", "bad-method",
            "method", "TaskPushNotificationConfig",
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

        assertEquals(200, response.statusCode());
        assertEquals(-32601, body.path("error").path("code").asInt());
        assertTrue(body.path("error").path("message").asText().contains("UnsupportedOperationError"));
    }

    @Test
    void unsupportedA2aVersionReturnsProblemJson() throws Exception {
        JsonNode card = clinicalCard();

        HttpResponse<String> response = httpClient.send(
            HttpRequest.newBuilder(URI.create(interfaceUrl(card, "JSONRPC")))
                .header("Content-Type", "application/json")
                .header("A2A-Version", "0.5")
                .POST(HttpRequest.BodyPublishers.ofString("{}"))
                .build(),
            HttpResponse.BodyHandlers.ofString()
        );
        JsonNode body = MAPPER.readTree(response.body());

        assertEquals(400, response.statusCode());
        assertEquals("Protocol Version Not Supported", body.path("title").asText());
        assertTrue(body.path("supportedVersions").toString().contains("1.0"));
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
