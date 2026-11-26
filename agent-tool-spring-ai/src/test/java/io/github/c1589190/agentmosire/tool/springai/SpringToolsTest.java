package io.github.c1589190.agentmosire.tool.springai;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.metadata.ToolMetadata;

import io.github.c1589190.agentmosire.tool.InvocationContext;
import io.github.c1589190.agentmosire.tool.JsonValues;
import io.github.c1589190.agentmosire.tool.RolePolicy;
import io.github.c1589190.agentmosire.tool.ToolId;
import io.github.c1589190.agentmosire.tool.ToolInvoker;
import io.github.c1589190.agentmosire.tool.ToolModule;
import io.github.c1589190.agentmosire.tool.ToolRegistry;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SpringToolsTest {
    @Test
    void importedConfirmationIsStillEnforcedByTheInvocationBoundary() {
        var called = new AtomicInteger();
        var springTool = new ToolCallback() {
            @Override
            public ToolDefinition getToolDefinition() {
                return ToolDefinition.builder().name("sample").description("Sample")
                        .inputSchema("{\"type\":\"object\"}").build();
            }

            @Override
            public ToolMetadata getToolMetadata() {
                return ToolMetadata.builder().requiresConfirmation(true).build();
            }

            @Override
            public String call(String input) {
                return "called " + called.incrementAndGet();
            }
        };
        var id = new ToolId("existing", "sample");
        var wrapped = SpringTools.importCallback(id, springTool, false, args -> List.of(),
                (caller, args) -> io.github.c1589190.agentmosire.tool.ToolDefinition.Verdict.ALLOW);
        var policy = new RolePolicy(caller -> caller.principal().equals("owner") ? Set.of("reader") : Set.of(),
                List.of(new RolePolicy.Grant("reader", Set.of(id), resource -> false)));
        var invoker = new ToolInvoker(new ToolRegistry(List.of(new ToolModule("existing", List.of(wrapped)))), policy);

        var owner = SpringTools.callback(invoker, id, InvocationContext.create("owner", "agent"));
        assertEquals("APPROVAL_REQUIRED", JsonValues.parseObject(owner.call("{}")).get("code"));
        assertEquals("INVALID_ARGUMENTS", JsonValues.parseObject(owner.call("[]")).get("code"));
        var visitor = SpringTools.callback(invoker, id, InvocationContext.create("visitor", "agent"));
        assertEquals("DENIED", JsonValues.parseObject(visitor.call("{}")).get("code"));
        assertEquals(0, called.get());
        assertEquals(Map.of(), invoker.invoke(id, Map.of(), InvocationContext.create("owner", "java")).data());
    }
}
