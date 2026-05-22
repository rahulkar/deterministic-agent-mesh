package com.agentmesh.deterministic.schema;

public class AgentPayloadException extends RuntimeException {
    public AgentPayloadException(String message) {
        super(message);
    }

    public AgentPayloadException(String message, Throwable cause) {
        super(message, cause);
    }
}
