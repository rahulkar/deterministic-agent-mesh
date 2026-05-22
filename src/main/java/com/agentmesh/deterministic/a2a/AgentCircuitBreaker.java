package com.agentmesh.deterministic.a2a;

import com.agentmesh.deterministic.agents.AgentId;
import com.agentmesh.deterministic.schema.AgentPayloadException;
import java.time.Clock;
import java.time.Duration;
import java.util.EnumMap;
import java.util.Map;

final class AgentCircuitBreaker {
    private final int failureThreshold;
    private final Duration cooldown;
    private final Clock clock;
    private final Map<AgentId, State> states = new EnumMap<>(AgentId.class);

    AgentCircuitBreaker() {
        this(
            Integer.getInteger("agentmesh.a2a.circuitBreakerFailures", 3),
            Duration.ofMillis(Long.getLong("agentmesh.a2a.circuitBreakerCooldownMillis", 30_000L)),
            Clock.systemUTC()
        );
    }

    AgentCircuitBreaker(int failureThreshold, Duration cooldown, Clock clock) {
        this.failureThreshold = Math.max(1, failureThreshold);
        this.cooldown = cooldown == null || cooldown.isNegative() ? Duration.ofSeconds(30) : cooldown;
        this.clock = clock;
    }

    synchronized void beforeCall(AgentId agentId) {
        State state = states.get(agentId);
        if (state == null || state.openedAtMillis == 0) {
            return;
        }
        long elapsed = clock.millis() - state.openedAtMillis;
        if (elapsed < cooldown.toMillis()) {
            throw new AgentPayloadException(agentId.wireName() + " circuit breaker is open");
        }
        states.remove(agentId);
    }

    synchronized void recordSuccess(AgentId agentId) {
        states.remove(agentId);
    }

    synchronized void recordFailure(AgentId agentId) {
        State current = states.getOrDefault(agentId, new State(0, 0));
        int failures = current.failures + 1;
        long openedAt = failures >= failureThreshold ? clock.millis() : 0;
        states.put(agentId, new State(failures, openedAt));
    }

    private record State(int failures, long openedAtMillis) {
    }
}
