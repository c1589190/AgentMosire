package io.github.c1589190.agentmosire.tool;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.networknt.schema.Schema;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;

public final class ToolRegistry {
    private final List<ToolDefinition> tools;
    private final Map<ToolId, Entry> entries;

    public ToolRegistry(Collection<ToolModule> modules) {
        Map<String, ToolModule> registeredModules = new LinkedHashMap<>();
        Map<String, ToolId> names = new LinkedHashMap<>();
        Map<ToolId, Entry> index = new LinkedHashMap<>();
        SchemaRegistry schemas = SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12);
        for (ToolModule module : modules) {
            if (registeredModules.putIfAbsent(module.id(), module) != null) {
                throw new IllegalArgumentException("Duplicate module: " + module.id());
            }
            for (ToolDefinition tool : module.tools()) {
                ToolId previous = names.putIfAbsent(tool.id().externalName(), tool.id());
                if (previous != null) {
                    throw new IllegalArgumentException("Conflicting tool names: " + previous + " and " + tool.id());
                }
                try {
                    Map<String, Object> schema = JsonValues.parseObject(tool.inputSchema());
                    if (!"object".equals(schema.get("type"))) {
                        throw new IllegalArgumentException("Tool input schema must describe an object");
                    }
                    requireLocalReferences(schema);
                    Schema compiled = schemas.getSchema(tool.inputSchema());
                    compiled.initializeValidators();
                    index.put(tool.id(), new Entry(tool, compiled));
                } catch (RuntimeException failure) {
                    throw new IllegalArgumentException("Invalid input schema for " + tool.id(), failure);
                }
            }
        }
        tools = index.values().stream().map(Entry::definition).toList();
        entries = Map.copyOf(index);
    }

    public List<ToolDefinition> tools() {
        return tools;
    }

    public Optional<ToolDefinition> find(ToolId id) {
        return Optional.ofNullable(entries.get(id)).map(Entry::definition);
    }

    Entry entry(ToolId id) {
        return entries.get(id);
    }

    private static void requireLocalReferences(Object node) {
        if (node instanceof Map<?, ?> map) {
            map.forEach((key, child) -> {
                if (("$ref".equals(key) || "$dynamicRef".equals(key) || "$recursiveRef".equals(key))
                        && (!(child instanceof String ref) || !ref.startsWith("#"))) {
                    throw new IllegalArgumentException("Tool schemas must use local references");
                }
                requireLocalReferences(child);
            });
        } else if (node instanceof List<?> list) {
            list.forEach(ToolRegistry::requireLocalReferences);
        }
    }

    record Entry(ToolDefinition definition, Schema schema) { }
}
