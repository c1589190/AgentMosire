package io.github.c1589190.agentmosire.workspace;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.AccessDeniedException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import io.github.c1589190.agentmosire.tool.Resource;
import io.github.c1589190.agentmosire.tool.ToolDefinition;
import io.github.c1589190.agentmosire.tool.ToolId;
import io.github.c1589190.agentmosire.tool.ToolImage;
import io.github.c1589190.agentmosire.tool.ToolModule;
import io.github.c1589190.agentmosire.tool.ToolResult;

import static io.github.c1589190.agentmosire.tool.ToolResult.Code.*;

public final class WorkspaceTools {
    public static final ToolId LIST = new ToolId("workspace", "list");
    public static final ToolId READ = new ToolId("workspace", "read");
    public static final ToolId WRITE = new ToolId("workspace", "write");
    public static final ToolId VIEW_IMAGE = new ToolId("workspace", "view_image");

    private static final int MAX_TEXT_BYTES = 1024 * 1024;
    private static final String READ_SCHEMA = """
            {"type":"object","properties":{"path":{"type":"string","minLength":1,"maxLength":1024}},
            "required":["path"],"additionalProperties":false}
            """;
    private static final String WRITE_SCHEMA = """
            {"type":"object","properties":{"path":{"type":"string","minLength":1,"maxLength":1024},
            "text":{"type":"string","maxLength":1048576}},"required":["path","text"],"additionalProperties":false}
            """;
    private static final byte[] PNG = {(byte) 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a};

    public record Mount(String name, Path root, boolean writable) {
        public Mount {
            if (!Objects.requireNonNull(name).matches("[a-z][a-z0-9_-]{0,31}")) {
                throw new IllegalArgumentException("Invalid workspace mount name");
            }
            Objects.requireNonNull(root);
        }
    }

    private final Map<String, Mount> mounts;

    public WorkspaceTools(Collection<Mount> mounts) throws IOException {
        var byName = new HashMap<String, Mount>();
        for (var mount : mounts) {
            var realRoot = mount.root().toRealPath();
            if (!Files.isDirectory(realRoot) || byName.putIfAbsent(mount.name(),
                    new Mount(mount.name(), realRoot, mount.writable())) != null) {
                throw new IllegalArgumentException("Each mount must identify a directory with a unique name");
            }
        }
        this.mounts = Map.copyOf(byName);
    }

    public ToolModule module() {
        return new ToolModule("workspace", List.of(
                definition(LIST, "List immediate children of a mounted virtual directory such as reference", "list", READ_SCHEMA, this::list),
                definition(READ, "Read a UTF-8 text file at mount/relative-path", "read", READ_SCHEMA, this::read),
                definition(WRITE, "Create or replace a UTF-8 text file in a writable mount. Its parent must exist", "write", WRITE_SCHEMA, this::write),
                definition(VIEW_IMAGE, "Show an image at mount/relative-path to the model", "read", READ_SCHEMA, this::image)));
    }

    private ToolDefinition definition(ToolId id, String description, String action, String schema, Operation operation) {
        return new ToolDefinition(id, description, schema, true,
                args -> List.of(new Resource("workspace", (String) args.get("path"), action)),
                (caller, args) -> ToolDefinition.Verdict.ALLOW,
                (args, access) -> {
                    try {
                        var path = resolve((String) args.get("path"));
                        return operation.apply(path, args);
                    } catch (NoSuchFileException missing) {
                        return ToolResult.of(NOT_FOUND, "Workspace entry was not found");
                    } catch (AccessDeniedException denied) {
                        return ToolResult.of(DENIED, "Workspace access was denied");
                    } catch (IllegalArgumentException invalid) {
                        return ToolResult.of(INVALID_ARGUMENTS, invalid.getMessage());
                    } catch (IOException failure) {
                        return ToolResult.of(FAILED, "Workspace operation failed");
                    }
                });
    }

    private ToolResult list(Location path, Map<String, Object> args) throws IOException {
        var listing = WorkspaceFiles.list(path.physical(), path.virtual());
        return new ToolResult(OK, "Workspace entries",
                Map.of("entries", listing.entries(), "truncated", listing.truncated()));
    }

    private ToolResult read(Location path, Map<String, Object> args) throws IOException {
        byte[] bytes = WorkspaceFiles.read(path.file(), MAX_TEXT_BYTES);
        var text = StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString();
        return new ToolResult(OK, "Workspace text", Map.of("path", path.virtual(), "text", text));
    }

    private ToolResult write(Location path, Map<String, Object> args) throws IOException {
        if (!path.mount().writable()) {
            return ToolResult.of(DENIED, "The mount is read-only");
        }
        var text = StandardCharsets.UTF_8.newEncoder().encode(java.nio.CharBuffer.wrap((String) args.get("text")));
        if (text.remaining() > MAX_TEXT_BYTES) {
            return ToolResult.of(INVALID_ARGUMENTS, "The text exceeds 1 MiB");
        }
        WorkspaceFiles.write(path.file(), text);
        return new ToolResult(OK, "Workspace file saved", Map.of("path", path.virtual()));
    }

    private ToolResult image(Location path, Map<String, Object> args) throws IOException {
        byte[] bytes = WorkspaceFiles.read(path.file(), ToolImage.MAX_BYTES);
        String type;
        if (startsWith(bytes, 0, PNG)) {
            type = "image/png";
        } else if (startsWith(bytes, 0, new byte[] {(byte) 0xff, (byte) 0xd8, (byte) 0xff})) {
            type = "image/jpeg";
        } else if (startsWith(bytes, 0, "GIF87a".getBytes(StandardCharsets.US_ASCII))
                || startsWith(bytes, 0, "GIF89a".getBytes(StandardCharsets.US_ASCII))) {
            type = "image/gif";
        } else if (startsWith(bytes, 0, "RIFF".getBytes(StandardCharsets.US_ASCII))
                && startsWith(bytes, 8, "WEBP".getBytes(StandardCharsets.US_ASCII))) {
            type = "image/webp";
        } else {
            return ToolResult.of(INVALID_ARGUMENTS, "Expected a PNG, JPEG, GIF or WebP image");
        }
        return new ToolResult(OK, "Workspace image " + path.virtual(), Map.of("path", path.virtual()),
                List.of(new ToolImage(type, bytes)));
    }

    private Location resolve(String virtual) throws IOException {
        var segments = virtual.split("/", -1);
        if (segments.length > 32 || Arrays.stream(segments).anyMatch(segment -> !validSegment(segment))) {
            throw new IllegalArgumentException("Expected a mounted virtual path without traversal");
        }
        var mount = mounts.get(segments[0]);
        if (mount == null) {
            throw new NoSuchFileException(virtual);
        }
        Path physical = mount.root();
        for (int index = 1; index < segments.length; index++) {
            physical = physical.resolve(segments[index]);
        }
        return new Location(mount, virtual, physical);
    }

    static boolean validSegment(String segment) {
        return !segment.isEmpty() && !segment.equals(".") && !segment.equals("..")
                && !segment.startsWith(WorkspaceFiles.STAGING_PREFIX)
                && segment.length() <= 255 && segment.chars().noneMatch(ch -> ch < 32 || ch == '\\' || ch == ':');
    }

    @FunctionalInterface
    private interface Operation {
        ToolResult apply(Location path, Map<String, Object> args) throws IOException;
    }

    private record Location(Mount mount, String virtual, Path physical) {
        Path file() {
            if (physical.equals(mount.root())) {
                throw new IllegalArgumentException("A file path within the mount is required");
            }
            return physical;
        }
    }

    private static boolean startsWith(byte[] value, int offset, byte[] prefix) {
        return value.length >= offset + prefix.length
                && Arrays.equals(value, offset, offset + prefix.length, prefix, 0, prefix.length);
    }
}
