package io.github.c1589190.agentmosire.example.catalog;

import java.util.List;
import java.util.Map;

import io.github.c1589190.agentmosire.tool.Resource;
import io.github.c1589190.agentmosire.tool.ToolDefinition;
import io.github.c1589190.agentmosire.tool.ToolId;
import io.github.c1589190.agentmosire.tool.ToolModule;
import io.github.c1589190.agentmosire.tool.ToolResult;

public final class CatalogTools {
    public static final ToolId FIND = new ToolId("catalog", "find");
    public static final ToolId INTERNAL = new ToolId("catalog", "internal_status");
    private static final String FIND_SCHEMA = """
            {"type":"object","properties":{"item":{"type":"string","minLength":1}},
             "required":["item"],"additionalProperties":false}
            """;

    private CatalogTools() {
    }

    public static ToolModule module() {
        return new ToolModule("catalog", List.of(
                new ToolDefinition(FIND, "Find an item in the sample catalog", FIND_SCHEMA, true,
                        args -> List.of(new Resource("item", (String) args.get("item"), "read")),
                        (caller, arguments) -> ToolDefinition.Verdict.ALLOW,
                        (args, access) -> new ToolResult(ToolResult.Code.OK, "Catalog item loaded",
                                Map.of("item", access.resources().getFirst().id()))),
                new ToolDefinition(INTERNAL, "Check the internal catalog status", "{\"type\":\"object\"}", false,
                        args -> List.of(), (caller, arguments) -> ToolDefinition.Verdict.ALLOW,
                        (args, access) -> ToolResult.ok("Catalog is ready"))));
    }
}
