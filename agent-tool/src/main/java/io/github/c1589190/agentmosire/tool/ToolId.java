package io.github.c1589190.agentmosire.tool;

import java.util.Objects;
import java.util.regex.Pattern;

public record ToolId(String module, String name) {
    private static final Pattern NAME = Pattern.compile("[a-z][a-z0-9_]{0,23}");

    public ToolId {
        validate(module);
        validate(name);
    }

    public String externalName() {
        return module + "__" + name;
    }

    private static void validate(String value) {
        if (!NAME.matcher(Objects.requireNonNull(value)).matches()) {
            throw new IllegalArgumentException("Invalid tool identifier segment: " + value);
        }
    }
}
