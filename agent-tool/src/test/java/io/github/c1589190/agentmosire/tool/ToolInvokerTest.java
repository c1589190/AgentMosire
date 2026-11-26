package io.github.c1589190.agentmosire.tool;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;

import static io.github.c1589190.agentmosire.tool.ToolResult.Code.*;
import static org.junit.jupiter.api.Assertions.*;

class ToolInvokerTest {
    private static final ToolId TOOL = new ToolId("goods", "label");
    private static final InvocationContext CALLER = InvocationContext.create("alice", "agent");

    @Test
    void validatesAndRechecksOriginalCallBeforeApprovedExecution() {
        var counter = new AtomicInteger();
        var role = new AtomicReference<>(Set.of("writer"));
        var policy = new RolePolicy(context -> role.get(), List.of(
                new RolePolicy.Grant("writer", Set.of(TOOL), resource -> resource.id().equals("alpha"))));
        var tool = new ToolDefinition(TOOL, "Apply a label", """
                {"type":"object","properties":{"item":{"type":"string"}},
                 "required":["item"],"additionalProperties":false}
                """, true, args -> List.of(new Resource("goods", (String) args.get("item"), "label")),
                (caller, args) -> ToolDefinition.Verdict.ASK,
                (args, access) -> ToolResult.ok("executed " + counter.incrementAndGet()));
        var invoker = new ToolInvoker(new ToolRegistry(List.of(new ToolModule("goods", List.of(tool)))), policy);
        var claims = new ArrayList<ToolInvoker.DeferredCall>();
        assertEquals(INVALID_ARGUMENTS, invoker.invoke(TOOL, Map.of("item", 1), CALLER).code());
        assertEquals(DENIED, invoker.invoke(TOOL, Map.of("item", "beta"), CALLER, claims::add).code());
        assertTrue(claims.isEmpty());
        assertEquals(APPROVAL_REQUIRED, invoker.invoke(TOOL, Map.of("item", "alpha"), CALLER).code());
        Map<String, Object> original = new LinkedHashMap<>(Map.of("item", "alpha"));
        assertEquals(DECISION_QUEUED, invoker.invoke(TOOL, original, CALLER, claims::add).code());
        original.put("item", "beta");
        assertEquals(Map.of("item", "alpha"), claims.getFirst().arguments());
        assertEquals(0, counter.get());
        assertEquals(OK, invoker.executeApproved(claims.getFirst()).code());
        assertEquals(DENIED, invoker.executeApproved(claims.getFirst()).code());
        assertEquals(1, counter.get());
        invoker.invoke(TOOL, Map.of("item", "alpha"), CALLER, claims::add);
        role.set(Set.of());
        assertEquals(DENIED, invoker.executeApproved(claims.getLast()).code());
        assertEquals(1, counter.get());
    }

    @Test
    void rejectsConflictingNamesAndExternalSchemaReferences() {
        var first = definition(new ToolId("a__b", "c"), "{\"type\":\"object\"}");
        var second = definition(new ToolId("a", "b__c"), "{\"type\":\"object\"}");
        assertThrows(IllegalArgumentException.class, () -> new ToolRegistry(List.of(
                new ToolModule("a__b", List.of(first)), new ToolModule("a", List.of(second)))));
        var external = definition(TOOL, """
                {"type":"object","properties":{"item":{"$ref":"https://example.invalid/schema"}}}
                """);
        assertThrows(IllegalArgumentException.class,
                () -> new ToolRegistry(List.of(new ToolModule("goods", List.of(external)))));
    }

    private static ToolDefinition definition(ToolId id, String schema) {
        return new ToolDefinition(id, "Tool", schema, false, args -> List.of(),
                (caller, args) -> ToolDefinition.Verdict.ALLOW, (args, access) -> ToolResult.ok("done"));
    }
}
