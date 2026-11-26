package io.github.c1589190.agentmosire.tool;

import java.util.Map;
import java.util.List;
import java.util.Objects;

public record ToolResult(Code code, String message, Map<String, Object> data, List<ToolImage> images) {
    public enum Code {
        OK, NOT_FOUND, INVALID_ARGUMENTS, DENIED, BLOCKED, APPROVAL_REQUIRED,
        DECISION_QUEUED, CANCELLED, FAILED, UNKNOWN
    }

    public ToolResult {
        Objects.requireNonNull(code);
        Objects.requireNonNull(message);
        data = JsonValues.copyObject(data);
        images = images == null ? List.of() : List.copyOf(images);
        if ((code != Code.OK && !images.isEmpty())
                || images.stream().mapToLong(ToolImage::size).sum() > 2L * ToolImage.MAX_BYTES || images.size() > 4) {
            throw new IllegalArgumentException("Image attachments exceed the tool result limits");
        }
    }

    public ToolResult(Code code, String message, Map<String, Object> data) {
        this(code, message, data, List.of());
    }

    public static ToolResult of(Code code, String message) {
        return new ToolResult(code, message, Map.of());
    }

    public static ToolResult ok(String message) {
        return of(Code.OK, message);
    }

    public static ToolResult okImage(String message, ToolImage image) {
        return new ToolResult(Code.OK, message, Map.of(), List.of(image));
    }

    public Map<String, Object> structuredContent() {
        return Map.of("code", code.name(), "message", message, "data", data);
    }
}
