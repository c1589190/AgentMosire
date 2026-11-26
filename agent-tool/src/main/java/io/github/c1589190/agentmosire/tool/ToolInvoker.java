package io.github.c1589190.agentmosire.tool;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import java.util.function.Predicate;

import com.networknt.schema.InputFormat;

import static io.github.c1589190.agentmosire.tool.ToolResult.Code.*;

public final class ToolInvoker {
    private final ToolRegistry registry;
    private final AccessPolicy policy;

    public ToolInvoker(ToolRegistry registry, AccessPolicy policy) {
        this.registry = Objects.requireNonNull(registry);
        this.policy = Objects.requireNonNull(policy);
    }

    public ToolRegistry registry() {
        return registry;
    }

    public boolean isVisible(ToolId tool, InvocationContext context) {
        try {
            return context != null && registry.find(tool).isPresent() && policy.allowsTool(context, tool);
        } catch (RuntimeException invalidPolicy) {
            return false;
        }
    }

    public ToolResult invoke(ToolId tool, Map<String, ?> arguments, InvocationContext context) {
        return invoke(tool, arguments, context, null);
    }

    public ToolResult invoke(ToolId tool, Map<String, ?> arguments, InvocationContext context,
            Predicate<DeferredCall> defer) {
        return invoke(tool, arguments, context, defer, () -> true);
    }

    public ToolResult invoke(ToolId tool, Map<String, ?> arguments, InvocationContext context,
            Predicate<DeferredCall> defer, BooleanSupplier active) {
        if (context == null) {
            return ToolResult.of(DENIED, "A trusted caller is required");
        }
        ToolRegistry.Entry entry = registry.entry(tool);
        if (entry == null) {
            return ToolResult.of(NOT_FOUND, "Tool is not registered");
        }
        if (!isVisible(tool, context)) {
            return ToolResult.of(DENIED, "Tool is outside the caller's permissions");
        }

        Map<String, Object> snapshot;
        try {
            snapshot = JsonValues.copyObject(arguments);
            if (!entry.schema().validate(JsonValues.write(snapshot), InputFormat.JSON).isEmpty()) {
                return ToolResult.of(INVALID_ARGUMENTS, "Arguments do not conform to the input schema");
            }
        } catch (RuntimeException invalidInput) {
            return ToolResult.of(INVALID_ARGUMENTS, "Expected valid JSON arguments");
        }
        return evaluate(entry.definition(), snapshot, context, defer, false, active);
    }

    /**
     * Executes a previously deferred call. The receipt can authorize it once, on the
     * invoker that issued it, under its original caller and current access policy.
     */
    public ToolResult executeApproved(DeferredCall call) {
        Objects.requireNonNull(call);
        if (call.owner != this || !call.used.compareAndSet(false, true)) {
            return ToolResult.of(DENIED, "Approval is invalid or has already been used");
        }
        return evaluate(call.tool, call.arguments, call.context, null, true, () -> true);
    }

    private ToolResult evaluate(ToolDefinition tool, Map<String, Object> arguments, InvocationContext context,
            Predicate<DeferredCall> defer, boolean approved, BooleanSupplier active) {
        if (!isVisible(tool.id(), context)) {
            return ToolResult.of(DENIED, "Tool is outside the caller's permissions");
        }
        AuthorizedAccess access;
        ToolDefinition.Verdict verdict;
        try {
            List<Resource> resources = List.copyOf(tool.resources().resolve(arguments));
            access = new AuthorizedAccess(context, tool.id(), resources);
            resources.forEach(access::require);
            verdict = Objects.requireNonNull(tool.gate().evaluate(context, arguments));
        } catch (ResourceDenied denial) {
            return ToolResult.of(DENIED, "Resource is outside the caller's permissions");
        } catch (RuntimeException failure) {
            return ToolResult.of(BLOCKED, "Tool authorization could not be evaluated");
        }

        if (verdict == ToolDefinition.Verdict.BLOCK) {
            return ToolResult.of(BLOCKED, "Tool policy blocked the call");
        }
        if (verdict == ToolDefinition.Verdict.ASK && !approved) {
            if (defer == null) {
                return ToolResult.of(APPROVAL_REQUIRED, "This call requires approval");
            }
            return defer.test(new DeferredCall(this, tool, context, arguments))
                    ? ToolResult.of(DECISION_QUEUED, "Call queued for a decision package")
                    : ToolResult.of(CANCELLED, "The caller is no longer accepting decisions");
        }

        if (!active.getAsBoolean()) {
            return ToolResult.of(CANCELLED, "The caller is no longer accepting tool work");
        }
        try {
            return Objects.requireNonNull(tool.handler().execute(arguments, access));
        } catch (ResourceDenied denied) {
            return ToolResult.of(DENIED, "Resource is outside the caller's permissions");
        } catch (RuntimeException failure) {
            return ToolResult.of(FAILED, "Tool execution failed");
        }
    }

    public static final class DeferredCall {
        private final ToolInvoker owner;
        private final ToolDefinition tool;
        private final InvocationContext context;
        private final Map<String, Object> arguments;
        private final AtomicBoolean used = new AtomicBoolean();

        private DeferredCall(ToolInvoker owner, ToolDefinition tool, InvocationContext context,
                Map<String, Object> arguments) {
            this.owner = owner;
            this.tool = tool;
            this.context = context;
            this.arguments = arguments;
        }

        public ToolId tool() {
            return tool.id();
        }

        public InvocationContext caller() {
            return context;
        }

        public Map<String, Object> arguments() {
            return arguments;
        }
    }

    private final class AuthorizedAccess implements ToolDefinition.Access {
        private final InvocationContext caller;
        private final ToolId tool;
        private final List<Resource> resources;

        private AuthorizedAccess(InvocationContext caller, ToolId tool, List<Resource> resources) {
            this.caller = caller;
            this.tool = tool;
            this.resources = resources;
        }

        @Override
        public InvocationContext caller() {
            return caller;
        }

        @Override
        public List<Resource> resources() {
            return resources;
        }

        @Override
        public void require(Resource resource) {
            try {
                if (policy.allowsResource(caller, tool, Objects.requireNonNull(resource))) {
                    return;
                }
            } catch (RuntimeException invalidPolicy) {
                throw new ResourceDenied();
            }
            throw new ResourceDenied();
        }
    }

    private static final class ResourceDenied extends RuntimeException { }
}
