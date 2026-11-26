package io.github.c1589190.agentmosire.tool;

import java.util.Map;
import java.util.Objects;

public record ToolResult(Code code, String message, Map<String, Object> data) {
    public enum Code {
        OK, NOT_FOUND, INVALID_ARGUMENTS, DENIED, BLOCKED, APPROVAL_REQUIRED,
        DECISION_QUEUED, CANCELLED, FAILED, UNKNOWN
    }

    public ToolResult {
        Objects.requireNonNull(code);
        Objects.requireNonNull(message);
        data = JsonValues.copyObject(data);
    }

    public static ToolResult of(Code code, String message) {
        return new ToolResult(code, message, Map.of());
    }

    public static ToolResult ok(String message) {
        return of(Code.OK, message);
    }
}
