package io.github.c1589190.agentmosire.harness;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import io.github.c1589190.agentmosire.tool.Resource;
import io.github.c1589190.agentmosire.tool.RolePolicy;
import io.github.c1589190.agentmosire.tool.ToolDefinition;
import io.github.c1589190.agentmosire.tool.ToolId;
import io.github.c1589190.agentmosire.tool.ToolModule;
import io.github.c1589190.agentmosire.tool.ToolResult;

final class Notes {
    static final ToolId READ = new ToolId("note", "read");
    static final ToolId WRITE = new ToolId("note", "write");
    static final Set<ToolId> TOOLS = Set.of(READ, WRITE);

    private static final Resource READ_NOTE = new Resource("note", "current", "read");
    private static final Resource WRITE_NOTE = new Resource("note", "current", "write");

    private Notes() {
    }

    static ToolModule module() {
        var note = new AtomicReference<>("");
        var read = new ToolDefinition(READ, "Read the current session note.",
                "{\"type\":\"object\",\"properties\":{},\"additionalProperties\":false}", false,
                args -> List.of(READ_NOTE), (caller, args) -> ToolDefinition.Verdict.ALLOW,
                (args, access) -> new ToolResult(ToolResult.Code.OK, "当前便笺", Map.of("text", note.get())));
        var write = new ToolDefinition(WRITE, "Replace the current session note with the given text.",
                """
                {"type":"object","properties":{"text":{"type":"string","minLength":1,"maxLength":4000}},
                 "required":["text"],"additionalProperties":false}
                """, false, args -> List.of(WRITE_NOTE), (caller, args) -> ToolDefinition.Verdict.ASK,
                (args, access) -> {
                    note.set((String) args.get("text"));
                    return ToolResult.ok("便笺已保存");
                });
        return new ToolModule("note", List.of(read, write));
    }

    static RolePolicy policy(String principal) {
        return new RolePolicy(caller -> principal.equals(caller.principal()) ? Set.of("note-user") : Set.of(),
                List.of(new RolePolicy.Grant("note-user", Set.of(READ), READ_NOTE::equals),
                        new RolePolicy.Grant("note-user", Set.of(WRITE), WRITE_NOTE::equals)));
    }
}
