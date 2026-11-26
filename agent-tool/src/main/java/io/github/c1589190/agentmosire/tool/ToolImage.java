package io.github.c1589190.agentmosire.tool;

import java.util.Arrays;
import java.util.Objects;

/** An immutable image attachment carrying its bytes and media type. */
public record ToolImage(String mimeType, byte[] bytes) {
    public static final int MAX_BYTES = 4 * 1024 * 1024;

    public ToolImage {
        Objects.requireNonNull(mimeType);
        if (!mimeType.matches("image/(png|jpeg|gif|webp)") || bytes.length == 0 || bytes.length > MAX_BYTES) {
            throw new IllegalArgumentException("Expected a supported image of at most 4 MiB");
        }
        bytes = bytes.clone();
    }

    @Override
    public byte[] bytes() {
        return bytes.clone();
    }

    public int size() {
        return bytes.length;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof ToolImage image && mimeType.equals(image.mimeType) && Arrays.equals(bytes, image.bytes);
    }

    @Override
    public int hashCode() {
        return 31 * mimeType.hashCode() + Arrays.hashCode(bytes);
    }
}
