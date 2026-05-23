package com.agentmesh.deterministic.a2a;

import com.agentmesh.deterministic.agents.AgentId;
import java.util.List;
import java.util.Map;
import org.a2aproject.sdk.spec.AgentCapabilities;
import org.a2aproject.sdk.spec.AgentCard;
import org.a2aproject.sdk.spec.AgentInterface;
import org.a2aproject.sdk.spec.AgentProvider;
import org.a2aproject.sdk.spec.AgentSkill;
import org.a2aproject.sdk.spec.HTTPAuthSecurityScheme;
import org.a2aproject.sdk.spec.SecurityRequirement;
import org.a2aproject.sdk.spec.SecurityScheme;
import org.a2aproject.sdk.spec.TransportProtocol;

public final class AgentMeshA2aCards {
    public static final String PROTOCOL_VERSION = AgentInterface.CURRENT_PROTOCOL_VERSION;

    private AgentMeshA2aCards() {
    }

    public static AgentCard publicCard(AgentId agentId, String baseUrl, A2aServerPolicy policy) {
        AgentCard.Builder builder = AgentCard.builder()
            .name(agentId.wireName())
            .description(agentId.description())
            .provider(new AgentProvider("Deterministic Agent Mesh Demo", "http://localhost"))
            .version("1.0.0")
            .documentationUrl("https://a2a-protocol.org/latest/specification/")
            .capabilities(AgentCapabilities.builder()
                .streaming(false)
                .pushNotifications(false)
                .extendedAgentCard(false)
                .build())
            .defaultInputModes(List.of("text/plain"))
            .defaultOutputModes(List.of("application/json"))
            .supportedInterfaces(List.of(
                new AgentInterface(TransportProtocol.JSONRPC.asString(), slash(baseUrl)),
                new AgentInterface(TransportProtocol.HTTP_JSON.asString(), stripSlash(baseUrl))
            ))
            .skills(List.of(AgentSkill.builder()
                .id(agentId.wireName())
                .name(agentId.wireName())
                .description(agentId.description())
                .tags(List.of("pharma", "deterministic-demo", "wiremock-backed"))
                .examples(List.of("Aspirin safety triage", "Prompt injection short-circuit validation"))
                .inputModes(List.of("text/plain"))
                .outputModes(List.of("application/json"))
                .build()));

        if (policy.usesAuthentication()) {
            Map<String, SecurityScheme> schemes = Map.of(
                "agentmeshBearer",
                HTTPAuthSecurityScheme.builder()
                    .scheme("bearer")
                    .bearerFormat("opaque")
                    .description("Bearer token required for A2A message endpoints")
                    .build()
            );
            builder.securitySchemes(schemes)
                .securityRequirements(List.of(new SecurityRequirement(Map.of("agentmeshBearer", List.of()))));
        }
        return builder.build();
    }

    private static String slash(String baseUrl) {
        return stripSlash(baseUrl) + "/";
    }

    private static String stripSlash(String baseUrl) {
        return baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
    }
}
