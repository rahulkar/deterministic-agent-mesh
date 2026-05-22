package com.google.adk.web.controller;

import com.google.adk.sessions.BaseSessionService;
import com.google.adk.web.dto.AgentRunRequest;
import java.lang.reflect.Type;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.core.MethodParameter;
import org.springframework.http.HttpInputMessage;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.RequestBodyAdviceAdapter;

@ControllerAdvice
final class AgentRunRequestSessionAdvice extends RequestBodyAdviceAdapter {
    private static final String DEFAULT_USER_ID = "dev-ui-user";

    private final BaseSessionService sessionService;

    AgentRunRequestSessionAdvice(BaseSessionService sessionService) {
        this.sessionService = sessionService;
    }

    @Override
    public boolean supports(MethodParameter methodParameter, Type targetType, Class<? extends HttpMessageConverter<?>> converterType) {
        return targetType == AgentRunRequest.class;
    }

    @Override
    public Object afterBodyRead(Object body, HttpInputMessage inputMessage, MethodParameter parameter, Type targetType, Class<? extends HttpMessageConverter<?>> converterType) {
        if (body instanceof AgentRunRequest request && hasText(request.appName)) {
            if (!hasText(request.userId)) {
                request.userId = DEFAULT_USER_ID;
            }
            if (!hasText(request.sessionId)) {
                request.sessionId = UUID.randomUUID().toString();
            }
            ensureSession(request);
        }
        return body;
    }

    private void ensureSession(AgentRunRequest request) {
        boolean exists = sessionService
            .getSession(request.appName, request.userId, request.sessionId, Optional.empty())
            .isEmpty()
            .blockingGet();
        if (!exists) {
            return;
        }
        sessionService
            .createSession(request.appName, request.userId, new ConcurrentHashMap<>(), request.sessionId)
            .blockingGet();
    }

    private boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }
}
