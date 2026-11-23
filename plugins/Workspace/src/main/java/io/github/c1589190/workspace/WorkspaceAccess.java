package io.github.c1589190.workspace;

import com.fasterxml.jackson.databind.JsonNode;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import io.github.c1589190.agentlib.permission.ResourceScope;
import io.github.c1589190.agentlib.plugin.HostServices;
import io.github.c1589190.agentlib.plugin.PluginAgent;
import io.github.c1589190.agentlib.tool.ToolContext;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** Shared workspace service exported by the Workspace plugin. */
public final class WorkspaceAccess {
  private final Map<String, Path> roots = new LinkedHashMap<>();
  private final Map<String, String> lineages = new ConcurrentHashMap<>();
  private final CapabilityCatalog catalog = CapabilityCatalogs.inMemory();
  private final DefaultWorkspaceRegistry registry =
      DefaultWorkspaceRegistry.create(catalog, null, lineages::get);

  WorkspaceAccess(HostServices host) {
    Set<Path> protectedRoots = new LinkedHashSet<>();
    if (host.dataDirectory() != null) {
      addProtectedRoot(protectedRoots, host.dataDirectory());
    }
    if (host.pluginDirectory() != null) {
      Path pluginRoot = host.pluginDirectory().toAbsolutePath().normalize().getParent();
      if (pluginRoot != null) {
        addProtectedRoot(protectedRoots, pluginRoot);
      }
    }
    registry.protect(protectedRoots);
    JsonNode config = host.settings();
    JsonNode entries = config == null ? null : config.get("roots");
    if (entries == null) {
      return;
    }
    if (!entries.isObject()) {
      throw new IllegalArgumentException("workspace config.json 的 roots 必须是对象");
    }
    entries
        .fields()
        .forEachRemaining(
            entry -> {
              new MountName(entry.getKey());
              Path root = realDirectory(Path.of(text(entry.getValue(), "根目录")));
              registry.requireUnprotected(root);
              roots.put(entry.getKey(), root);
            });
  }

  /** Register a resource capability supplied by a dependent tool plugin. */
  public void register(Capability capability) {
    catalog.register(capability);
  }

  /** Return the single authorized virtual path resolver for this host. */
  @SuppressFBWarnings(
      value = "EI_EXPOSE_REP",
      justification = "Dependent plugins share this live, caller-checked registry by design.")
  public WorkspaceRegistry registry() {
    return registry;
  }

  Runnable attach(PluginAgent agent) {
    JsonNode settings = agent.settings();
    JsonNode declared = settings == null ? null : settings.get("mounts");
    if (settings != null && (!settings.isObject() || (declared != null && !declared.isObject()))) {
      throw new IllegalArgumentException("Agent 的 plugins.workspace.mounts 必须是对象");
    }
    Map<MountName, Mount> mounts = new LinkedHashMap<>();
    if (declared != null) {
      declared
          .fields()
          .forEachRemaining(
              entry -> {
                Mount mount = mount(agent, new MountName(entry.getKey()), entry.getValue());
                mounts.put(mount.name(), mount);
              });
    }
    registry.install(agent.instanceId(), agent.parentId(), mounts);
    lineages.put(agent.instanceId(), agent.lineage());
    return () -> {
      registry.detach(agent.instanceId());
      lineages.remove(agent.instanceId());
    };
  }

  private Mount mount(PluginAgent agent, MountName name, JsonNode settings) {
    String rootName = text(settings.get("root"), "root");
    Path root = roots.get(rootName);
    if (root == null) {
      throw new IllegalArgumentException("未知工作区根: " + rootName);
    }
    String relative = settings.path("subdir").asText("");
    Path directory = root.resolve(relative).normalize();
    if (Path.of(relative).isAbsolute() || !directory.startsWith(root)) {
      throw new IllegalArgumentException("工作区子路径超出配置根: " + name.value());
    }
    directory = realDirectory(directory);
    if (!directory.startsWith(root)) {
      throw new IllegalArgumentException("工作区真实路径超出配置根: " + name.value());
    }
    registry.requireUnprotected(directory);
    boolean readOnly =
        switch (settings.path("mode").asText("read-only")) {
          case "read-only" -> true;
          case "read-write" -> false;
          default -> throw new IllegalArgumentException("工作区 mode 必须是 read-only 或 read-write");
        };
    Kind kind = Kind.valueOf(settings.path("kind").asText("FILE").toUpperCase(Locale.ROOT));
    Set<String> capabilities = new LinkedHashSet<>();
    JsonNode list = settings.path("capabilities");
    if (!list.isArray() || list.isEmpty()) {
      throw new IllegalArgumentException("工作区必须指定非空 capabilities 数组");
    }
    list.forEach(
        value -> {
          String id = text(value, "capabilities");
          if (catalog.get(id).isEmpty()) {
            throw new IllegalArgumentException("工作区能力未安装: " + id);
          }
          capabilities.add(id);
        });
    Mount result =
        new Mount(
            name,
            agent.instanceId(),
            agent.instanceId(),
            directory,
            Resolvers.direct(),
            kind,
            capabilities,
            Origin.ASSEMBLY,
            Optional.empty(),
            readOnly);
    if (agent.parentId() != null
        && registry.mounts(agent.parentId()).stream()
            .noneMatch(parent -> DefaultWorkspaceRegistry.covers(parent, result))) {
      throw WorkspaceException.denied("子 Agent 的工作区超出父级挂载范围: " + name.value());
    }
    return result;
  }

  /** Return physical roots covered by the bound Agent, capability and resource scope. */
  public List<Path> paths(ToolContext context, String capability, String namespace, boolean write) {
    String id = context.identity().instanceId();
    ResourceScope scope =
        context
            .permissions()
            .resourceScopes()
            .narrowTo(registry.resourceLimits(id))
            .scopeOrUnrestricted(namespace)
            .narrowTo(context.permissions().fsScope());
    return registry.mounts(id).stream()
        .filter(mount -> mount.capabilities().contains(capability) && (!write || !mount.readOnly()))
        .flatMap(
            mount -> ResourceScope.of(mount.dir().toString()).narrowTo(scope).dirList().stream())
        .map(WorkspaceAccess::stableDirectory)
        .peek(registry::requireUnprotected)
        .distinct()
        .toList();
  }

  /** Resolve a virtual directory for an explicitly selected dependent tool. */
  public Path directory(ToolContext context, String virtualPath, String capability) {
    String id = context.identity().instanceId();
    MountName name = VirtualPath.parse(virtualPath).mount();
    if (registry.mounts(id).stream()
        .noneMatch(
            mount -> mount.name().equals(name) && mount.capabilities().contains(capability))) {
      throw WorkspaceException.denied("工具未获得该工作区的访问能力");
    }
    var permissions =
        context
            .permissions()
            .withResourceScopes(
                context.permissions().resourceScopes().narrowTo(registry.resourceLimits(id)));
    Path directory =
        registry
            .resolveForIo(
                virtualPath, WorkspaceCaller.of(id, permissions), IoMode.READ, SymlinkPolicy.REJECT)
            .realPath();
    if (!Files.isDirectory(directory)) {
      throw WorkspaceException.invalid("工作目录必须指向一个目录");
    }
    return directory;
  }

  /** A shell exposes one physical view, so incompatible aliases cannot share a command. */
  public boolean readOnlyConflicts(ToolContext context, String capability, List<Path> writable) {
    return registry.mounts(context.identity().instanceId()).stream()
        .filter(mount -> mount.readOnly() && mount.capabilities().contains(capability))
        .map(Mount::dir)
        .anyMatch(
            readOnly ->
                writable.stream()
                    .anyMatch(
                        writeRoot ->
                            writeRoot.startsWith(readOnly) || readOnly.startsWith(writeRoot)));
  }

  private static void addProtectedRoot(Set<Path> roots, Path path) {
    roots.add(path.toAbsolutePath().normalize());
    if (Files.isDirectory(path)) {
      try {
        roots.add(path.toRealPath());
      } catch (IOException failure) {
        throw new IllegalArgumentException("无法解析运行时目录: " + path, failure);
      }
    }
  }

  private static Path realDirectory(Path path) {
    try {
      if (!Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
        throw new IllegalArgumentException("工作区必须指向实体目录: " + path);
      }
      return path.toRealPath();
    } catch (IOException failure) {
      throw new IllegalArgumentException("无法解析工作区目录: " + path, failure);
    }
  }

  private static Path stableDirectory(Path path) {
    Path real = realDirectory(path);
    if (!path.equals(real)) {
      throw WorkspaceException.denied("执行面的物理目录已变化");
    }
    return real;
  }

  private static String text(JsonNode value, String name) {
    if (value == null || !value.isTextual() || value.asText().isBlank()) {
      throw new IllegalArgumentException("工作区 " + name + " 必须是非空字符串");
    }
    return value.asText();
  }
}
