package io.github.c1589190.agentmosire.tool.springai;

import java.util.Map;
import java.util.Objects;
import java.util.function.BooleanSupplier;
import java.util.function.Predicate;

import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;

import io.github.c1589190.agentmosire.tool.InvocationContext;
import io.github.c1589190.agentmosire.tool.JsonValues;
import io.github.c1589190.agentmosire.tool.ToolDefinition;
import io.github.c1589190.agentmosire.tool.ToolId;
import io.github.c1589190.agentmosire.tool.ToolInvoker;
import io.github.c1589190.agentmosire.tool.ToolResult;

import static io.github.c1589190.agentmosire.tool.ToolResult.Code.*;

public final class SpringTools {
    public static final String INVOCATION_CONTEXT = InvocationContext.class.getName();

    private SpringTools() {
    }

    /** Creates a request-scoped callback bound to a caller already verified by the host. */
    public static ToolCallback callback(ToolInvoker invoker, ToolId id, InvocationContext caller) {
        return callback(invoker, id, caller, null, () -> true);
    }

    public static ToolCallback callback(ToolInvoker invoker, ToolId id, InvocationContext caller,
            Predicate<ToolInvoker.DeferredCall> defer, BooleanSupplier active) {
        var tool = invoker.registry().find(id).orElseThrow();
        Objects.requireNonNull(caller);
        var definition = org.springframework.ai.tool.definition.ToolDefinition.builder()
                .name(id.externalName())
                .description(tool.description())
                .inputSchema(tool.inputSchema())
                .build();

        return new ToolCallback() {
            @Override
            public org.springframework.ai.tool.definition.ToolDefinition getToolDefinition() {
                return definition;
            }

            @Override
            public String call(String input) {
                if (!active.getAsBoolean()) {
                    return JsonValues.write(ToolResult.of(CANCELLED, "The turn has ended"));
                }
                Map<String, Object> arguments;
                try {
                    arguments = JsonValues.parseObject(input);
                } catch (RuntimeException invalidJson) {
                    return JsonValues.write(ToolResult.of(INVALID_ARGUMENTS, "Expected a JSON object"));
                }
                return JsonValues.write(invoker.invoke(id, arguments, caller, defer, active));
            }

            @Override
            public String call(String input, ToolContext context) {
                return call(input);
            }
        };
    }

    /** Adds an existing Spring tool through the same policy and invocation boundary. */
    public static ToolDefinition importCallback(
            ToolId id, ToolCallback callback, boolean exportable,
            ToolDefinition.Resources resources, ToolDefinition.Gate gate) {
        var definition = callback.getToolDefinition();
        return new ToolDefinition(id, definition.description(),
                definition.inputSchema(), exportable, resources, (caller, arguments) -> {
                    var verdict = gate.evaluate(caller, arguments);
                    if (verdict == ToolDefinition.Verdict.ALLOW && callback.getToolMetadata().requiresConfirmation()) {
                        return ToolDefinition.Verdict.ASK;
                    }
                    return verdict;
                }, (arguments, access) -> ToolResult.ok(callback.call(JsonValues.write(arguments),
                        new ToolContext(Map.of(INVOCATION_CONTEXT, access.caller())))));
    }
}
