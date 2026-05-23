package com.agentmesh.deterministic.a2a;

import com.agentmesh.deterministic.agents.AgentId;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Flow;
import org.a2aproject.sdk.jsonrpc.common.wrappers.ListTasksResult;
import org.a2aproject.sdk.server.ServerCallContext;
import org.a2aproject.sdk.server.requesthandlers.RequestHandler;
import org.a2aproject.sdk.spec.A2AError;
import org.a2aproject.sdk.spec.Artifact;
import org.a2aproject.sdk.spec.CancelTaskParams;
import org.a2aproject.sdk.spec.DataPart;
import org.a2aproject.sdk.spec.DeleteTaskPushNotificationConfigParams;
import org.a2aproject.sdk.spec.EventKind;
import org.a2aproject.sdk.spec.GetTaskPushNotificationConfigParams;
import org.a2aproject.sdk.spec.InternalError;
import org.a2aproject.sdk.spec.ListTaskPushNotificationConfigsParams;
import org.a2aproject.sdk.spec.ListTaskPushNotificationConfigsResult;
import org.a2aproject.sdk.spec.ListTasksParams;
import org.a2aproject.sdk.spec.Message;
import org.a2aproject.sdk.spec.MessageSendParams;
import org.a2aproject.sdk.spec.StreamingEventKind;
import org.a2aproject.sdk.spec.Task;
import org.a2aproject.sdk.spec.TaskIdParams;
import org.a2aproject.sdk.spec.TaskNotCancelableError;
import org.a2aproject.sdk.spec.TaskPushNotificationConfig;
import org.a2aproject.sdk.spec.TaskQueryParams;
import org.a2aproject.sdk.spec.TaskState;
import org.a2aproject.sdk.spec.TaskStatus;
import org.a2aproject.sdk.spec.TextPart;
import org.a2aproject.sdk.spec.UnsupportedOperationError;

public final class AgentMeshA2aRequestHandler implements RequestHandler {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final AgentId agentId;
    private final AgentMeshA2aExecutor executor;

    public AgentMeshA2aRequestHandler(AgentId agentId, String liteLlmBaseUrl) {
        this.agentId = agentId;
        this.executor = new AgentMeshA2aExecutor(agentId, liteLlmBaseUrl);
    }

    @Override
    public EventKind onMessageSend(MessageSendParams params, ServerCallContext context) throws A2AError {
        try {
            String prompt = text(params.message());
            JsonNode payload = executor.callLiteLlm(prompt);
            String taskId = params.message().messageId() == null || params.message().messageId().isBlank()
                ? agentId.wireName() + ":" + System.nanoTime()
                : params.message().messageId();
            Message agentMessage = Message.builder()
                .role(Message.Role.ROLE_AGENT)
                .messageId(agentId.wireName() + ":" + taskId)
                .parts(List.of(new DataPart(MAPPER.convertValue(payload, Object.class))))
                .build();
            Artifact artifact = Artifact.builder()
                .artifactId(agentId.wireName() + ":artifact:" + taskId)
                .name(agentId.wireName() + "-payload")
                .parts(List.of(new DataPart(MAPPER.convertValue(payload, Object.class))))
                .build();
            return Task.builder()
                .id(taskId)
                .contextId(params.message().contextId())
                .status(new TaskStatus(TaskState.TASK_STATE_COMPLETED, agentMessage, null))
                .artifacts(List.of(artifact))
                .history(List.of(params.message(), agentMessage))
                .metadata(Map.of("agent", agentId.wireName()))
                .build();
        } catch (IOException e) {
            throw new InternalError(agentId.wireName() + " failed payload processing: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new InternalError(agentId.wireName() + " call interrupted");
        }
    }

    @Override
    public Task onGetTask(TaskQueryParams params, ServerCallContext context) throws A2AError {
        throw new UnsupportedOperationError();
    }

    @Override
    public ListTasksResult onListTasks(ListTasksParams params, ServerCallContext context) throws A2AError {
        return new ListTasksResult(List.of());
    }

    @Override
    public Task onCancelTask(CancelTaskParams params, ServerCallContext context) throws A2AError {
        throw new TaskNotCancelableError();
    }

    @Override
    public Flow.Publisher<StreamingEventKind> onMessageSendStream(MessageSendParams params, ServerCallContext context) throws A2AError {
        throw new UnsupportedOperationError();
    }

    @Override
    public TaskPushNotificationConfig onCreateTaskPushNotificationConfig(TaskPushNotificationConfig params, ServerCallContext context) throws A2AError {
        throw new UnsupportedOperationError();
    }

    @Override
    public TaskPushNotificationConfig onGetTaskPushNotificationConfig(GetTaskPushNotificationConfigParams params, ServerCallContext context) throws A2AError {
        throw new UnsupportedOperationError();
    }

    @Override
    public Flow.Publisher<StreamingEventKind> onSubscribeToTask(TaskIdParams params, ServerCallContext context) throws A2AError {
        throw new UnsupportedOperationError();
    }

    @Override
    public ListTaskPushNotificationConfigsResult onListTaskPushNotificationConfigs(
        ListTaskPushNotificationConfigsParams params,
        ServerCallContext context
    ) throws A2AError {
        return new ListTaskPushNotificationConfigsResult(List.of());
    }

    @Override
    public void onDeleteTaskPushNotificationConfig(DeleteTaskPushNotificationConfigParams params, ServerCallContext context) throws A2AError {
        throw new UnsupportedOperationError();
    }

    @Override
    public void validateRequestedTask(String taskId) throws A2AError {
    }

    private String text(Message message) {
        List<String> values = new ArrayList<>();
        for (org.a2aproject.sdk.spec.Part<?> part : message.parts()) {
            if (part instanceof TextPart textPart) {
                values.add(textPart.text());
            }
        }
        return String.join("\n", values);
    }
}
