package io.github.c1589190.agentmosire.tool;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import tools.jackson.core.StreamReadFeature;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;

/** A transport-neutral JSON value boundary for tools and their input snapshots. */
public final class JsonValues {
    private static final JsonMapper MAPPER = JsonMapper.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
            .build();

    private JsonValues() {
    }

    public static Map<String, Object> parseObject(String input) {
        return copyObject(MAPPER.readValue(input, new TypeReference<Map<String, Object>>() { }));
    }

    public static String write(Object value) {
        return MAPPER.writeValueAsString(value);
    }

    public static Map<String, Object> copyObject(Map<String, ?> source) {
        return copyMap(source, 0);
    }

    private static Map<String, Object> copyMap(Map<?, ?> source, int depth) {
        Map<String, Object> copy = new LinkedHashMap<>();
        Objects.requireNonNull(source).forEach((key, value) -> {
            if (!(key instanceof String name)) {
                throw new IllegalArgumentException("JSON object keys must be strings");
            }
            copy.put(name, snapshot(value, depth + 1));
        });
        return Collections.unmodifiableMap(copy);
    }

    private static Object snapshot(Object value, int depth) {
        if (depth > 80) {
            throw new IllegalArgumentException("JSON input is too deeply nested");
        }
        return switch (value) {
            case null -> null;
            case String text -> text;
            case Boolean flag -> flag;
            case Byte number -> number;
            case Short number -> number;
            case Integer number -> number;
            case Long number -> number;
            case BigInteger number -> number;
            case BigDecimal number -> number;
            case Double number when Double.isFinite(number) -> number;
            case Float number when Float.isFinite(number) -> number;
            case Map<?, ?> map -> copyMap(map, depth);
            case List<?> list -> list.stream().map(item -> snapshot(item, depth + 1)).toList();
            default -> throw new IllegalArgumentException("Expected a JSON value");
        };
    }
}
