package io.github.c1589190.agentmosire.runtime;

import java.util.List;
import java.util.UUID;

public record TurnResult(UUID turnId, Status status, String text, List<UUID> decisionPackages) {
    public enum Status { COMPLETED, SESSION_BUSY, CLOSED, CANCELLED, TIMED_OUT, TOOL_LIMIT, FAILED }

    public TurnResult {
        decisionPackages = List.copyOf(decisionPackages);
    }
}
