package io.github.c1589190.agentmosire.workspace;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.github.c1589190.agentmosire.tool.InvocationContext;
import io.github.c1589190.agentmosire.tool.RolePolicy;
import io.github.c1589190.agentmosire.tool.ToolInvoker;
import io.github.c1589190.agentmosire.tool.ToolRegistry;

import static io.github.c1589190.agentmosire.tool.ToolResult.Code.*;
import static io.github.c1589190.agentmosire.workspace.WorkspaceTools.*;
import static org.junit.jupiter.api.Assertions.*;

class WorkspaceToolsTest {
    @TempDir
    Path root;

    @Test
    void mountsShareOneVirtualBoundaryAndKeepSeatPermissions() throws Exception {
        var reference = Files.createDirectory(root.resolve("reference"));
        var notes = Files.createDirectory(root.resolve("notes"));
        var privateFile = Files.writeString(root.resolve("outside.txt"), "outside");
        Files.createSymbolicLink(reference.resolve("escape"), privateFile);
        Files.createSymbolicLink(notes.resolve("escape"), root);
        Files.writeString(reference.resolve("地图.txt"), "reference");
        byte[] png = Base64.getDecoder().decode(
                "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+jLxQAAAAASUVORK5CYII=");
        Files.write(reference.resolve("map.png"), png);
        var workspace = new WorkspaceTools(List.of(new Mount("reference", reference, false),
                new Mount("notes", notes, true)));
        var ids = Set.of(LIST, READ, WRITE, VIEW_IMAGE);
        var policy = new RolePolicy(caller -> Set.of(caller.principal()), List.of(
                new RolePolicy.Grant("a", ids, resource -> true),
                new RolePolicy.Grant("b", ids, resource -> resource.id().startsWith("reference/"))));
        var invoker = new ToolInvoker(new ToolRegistry(List.of(workspace.module())), policy);
        var a = InvocationContext.create("a", "job-a");
        var b = InvocationContext.create("b", "job-b");
        assertEquals(List.of("reference/map.png", "reference/地图.txt"),
                invoker.invoke(LIST, Map.of("path", "reference"), a).data().get("entries"));
        assertEquals("reference", invoker.invoke(READ, Map.of("path", "reference/地图.txt"), b).data().get("text"));
        assertEquals(DENIED, invoker.invoke(WRITE, Map.of("path", "reference/地图.txt", "text", "other"), a).code());
        assertEquals(DENIED, invoker.invoke(READ, Map.of("path", "notes/private.txt"), b).code());
        assertEquals(INVALID_ARGUMENTS, invoker.invoke(READ, Map.of("path", "reference/../outside.txt"), a).code());
        assertEquals(DENIED, invoker.invoke(READ, Map.of("path", "reference/escape"), a).code());
        assertNotEquals(OK, invoker.invoke(WRITE, Map.of("path", "notes/escape/outside.txt", "text", "bad"), a).code());
        assertEquals("outside", Files.readString(privateFile));
        assertEquals(OK, invoker.invoke(WRITE, Map.of("path", "notes/private.txt", "text", "笔记"), a).code());
        assertEquals(OK, invoker.invoke(WRITE, Map.of("path", "notes/private.txt", "text", "新笔记"), a).code());
        assertEquals("新笔记", invoker.invoke(READ, Map.of("path", "notes/private.txt"), a).data().get("text"));
        var image = invoker.invoke(VIEW_IMAGE, Map.of("path", "reference/map.png"), a).images().getFirst();
        assertEquals("image/png", image.mimeType());
        assertArrayEquals(png, image.bytes());
        assertEquals(INVALID_ARGUMENTS, invoker.invoke(VIEW_IMAGE, Map.of("path", "reference/地图.txt"), a).code());
    }
}
