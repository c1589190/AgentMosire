package io.github.c1589190.agentmosire.runtime.decision;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import io.github.c1589190.agentmosire.tool.InvocationContext;
import io.github.c1589190.agentmosire.tool.JsonValues;
import io.github.c1589190.agentmosire.tool.ToolId;

public record DecisionPackage(UUID id, UUID turnId, String proposer, long version, Instant expiresAt,
        State state, List<Item> items, Map<Integer, Verdict> verdicts, DecisionReceipt receipt,
        InvocationContext caller) {
    public enum State { PENDING, DECIDED, EXECUTING, CLOSED, EXPIRED, CANCELLED }

    public record Item(int index, ToolId tool, Map<String, Object> arguments, String digest) {
        public Item {
            arguments = JsonValues.copyObject(arguments);
        }
    }

    public record Verdict(boolean approved, String approver, String note) {
        public Verdict(boolean approved, String approver) {
            this(approved, approver, "");
        }

        public Verdict {
            Objects.requireNonNull(approver);
            Objects.requireNonNull(note);
        }
    }

    public DecisionPackage {
        Objects.requireNonNull(caller);
        if (!caller.principal().equals(proposer) || !caller.requestId().equals(turnId)) {
            throw new IllegalArgumentException("Package identity must match the caller");
        }
        items = List.copyOf(items);
        verdicts = Map.copyOf(verdicts);
    }
}
