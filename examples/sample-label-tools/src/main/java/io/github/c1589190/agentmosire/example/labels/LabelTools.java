package io.github.c1589190.agentmosire.example.labels;

import java.util.List;
import java.util.Map;
import java.util.Objects;

import io.github.c1589190.agentmosire.tool.Resource;
import io.github.c1589190.agentmosire.tool.ToolDefinition;
import io.github.c1589190.agentmosire.tool.ToolId;
import io.github.c1589190.agentmosire.tool.ToolModule;
import io.github.c1589190.agentmosire.tool.ToolResult;

public final class LabelTools {
    public static final ToolId APPLY = new ToolId("labels", "apply");
    private static final String SCHEMA = """
            {"type":"object","properties":{
              "item":{"type":"string","minLength":1},"label":{"type":"string","minLength":1}},
              "required":["item","label"],"additionalProperties":false}
            """;

    private LabelTools() {
    }

    public static ToolModule module(Map<String, String> labels) {
        Objects.requireNonNull(labels);
        return new ToolModule("labels", List.of(
                new ToolDefinition(APPLY, "Apply a label to a sample catalog item", SCHEMA, false,
                        args -> List.of(new Resource("item", (String) args.get("item"), "label")),
                        (caller, arguments) -> ToolDefinition.Verdict.ASK,
                        (args, access) -> {
                            String item = access.resources().getFirst().id();
                            labels.put(item, (String) args.get("label"));
                            return ToolResult.ok("Label stored");
                        })));
    }
}
