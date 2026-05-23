package com.agentmesh.deterministic.a2a;

import com.agentmesh.deterministic.agents.AgentId;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.a2aproject.sdk.server.agentexecution.AgentExecutor;
import org.a2aproject.sdk.server.agentexecution.RequestContext;
import org.a2aproject.sdk.server.tasks.AgentEmitter;
import org.a2aproject.sdk.spec.A2AError;
import org.a2aproject.sdk.spec.DataPart;
import org.a2aproject.sdk.spec.InternalError;
import org.a2aproject.sdk.spec.TaskNotCancelableError;

public final class AgentMeshA2aExecutor implements AgentExecutor {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Duration TIMEOUT = Duration.ofSeconds(3);

    private final AgentId agentId;
    private final String liteLlmBaseUrl;
    private final HttpClient httpClient;

    public AgentMeshA2aExecutor(AgentId agentId, String liteLlmBaseUrl) {
        this.agentId = agentId;
        this.liteLlmBaseUrl = liteLlmBaseUrl;
        this.httpClient = HttpClient.newBuilder().connectTimeout(TIMEOUT).build();
    }

    @Override
    public void execute(RequestContext requestContext, AgentEmitter emitter) throws A2AError {
        try {
            JsonNode payload = callLiteLlm(requestContext.getUserInput(""));
            emitter.addArtifact(List.of(new DataPart(MAPPER.convertValue(payload, Object.class))));
            emitter.complete();
        } catch (IOException e) {
            throw new InternalError(agentId.wireName() + " failed payload processing: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new InternalError(agentId.wireName() + " call interrupted");
        }
    }

    @Override
    public void cancel(RequestContext requestContext, AgentEmitter emitter) throws A2AError {
        throw new TaskNotCancelableError();
    }

    JsonNode callLiteLlm(String prompt) throws IOException, InterruptedException {
        String requestBody = MAPPER.writeValueAsString(Map.of(
            "model", "litellm/mock",
            "messages", List.of(
                Map.of("role", "system", "content", "agent=" + agentId.wireName() + "; return exact JSON only"),
                Map.of("role", "user", "content", prompt == null ? "" : prompt)
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
        String content = MAPPER.readTree(response.body()).path("choices").path(0).path("message").path("content").asText();
        return MAPPER.readTree(content);
    }
}
