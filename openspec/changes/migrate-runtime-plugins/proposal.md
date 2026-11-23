# Proposal

## Why

把 ProjectMosire 的工作区、文档、搜索和 Bash 能力迁入 AgentMosire，使应用可以用同一宿主独立安装插件，也可以使用包含同版插件的本地整合包。

## What Changes

- 引入 `workspace`、`docs`、`search` 和 `bash` 四个 PF4J 插件。Docs 和 Bash 声明 Workspace 为必需依赖。
- Main 默认从 `<data-dir>/plugins` 发现插件 JAR，为每个插件提供 `<data-dir>/plugins/<plugin-id>/config.json` 和独立数据目录。
- Workspace 持有共享的挂载与路径鉴权服务。宿主配置可为多个 Agent 分别挂载同一只读目录，且每个工具仍受本次执行的限制。
- 构建可直接启动的宿主 JAR、独立插件 JAR 和包含同一组 JAR 的本地 ZIP。

## Capabilities

### New Capabilities

- `runtime-plugins`：发现、配置、依赖启动和本地打包。
- `workspace-plugin-access`：按调用者解析挂载、共享只读目录并强制执行边界。

### Modified Capabilities

- 无。首期变更中已有的 `runtime-host` 和 `tool-authorization` 契约继续适用。

## Impact

涉及 Maven reactor、Main 插件装配、AgentLib 扩展契约及四个迁入模块。宿主和 Brain 的生产代码继续通过通用契约接入插件。原始导入基线为 ProjectMosire `df1b4202755127b4e86c29a73de7bd9944eed1e5`。
