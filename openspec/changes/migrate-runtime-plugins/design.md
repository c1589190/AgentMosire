# Design

## Context

当前 Main 从宿主 JAR 旁逐文件加载插件。原仓 Workspace 和 Docs 由运行时直接装配，Search 和 Bash 有 PF4J 入口。当前加载循环与原仓装配位置分别见 `AgentLib/.../plugin/PluginToolSource.java` 和 `ProjectMosire/BrainMosire/.../runtime/WorkspaceToolModules.java`。

## Goals / Non-Goals

宿主持有目录解析、插件生命周期和通用输入。Workspace 持有按运行实例隔离的挂载表。Docs、Bash 与 Search 各自持有工具、配置语义和执行资源。制品从 Maven reactor 本地生成。

## Decisions

1. Main 按 `<data-dir>/plugins/<id>/config.json` 读取 JSON 对象，把插件专属目录和配置副本交给扩展。不存在的配置沿用该插件缺省值。安装在根目录一层的 JAR 才参与扫描。配置文件路径和插件 ID 在宿主边界校验。旧宿主的 `tools.<id>` 配置作为缺文件时的兼容输入。
2. 先读取候选描述符，再按必需依赖顺序装载和初始化。依赖插件可通过扩展契约发布类型化服务。Main 通过通用生命周期处理 Agent 启停，调用者身份以宿主绑定为准。
3. Workspace 插件通过插件配置定义宿主可挂载的物理目录，Agent 配置用虚拟挂载名引用根并声明读写模式。相同物理目录可以落入多个 Agent 的独立挂载视图。子级挂载受父级视图、宿主配置和执行面的交集约束。不可访问的路径按拒绝处理。
4. Docs 的写操作经 Workspace 的写访问校验。Bash 按 Workspace 返回的访问范围构造进程文件系统；需要隔离而宿主能力不足时，拒绝启动命令。主数据和插件目录受宿主保护。
5. 整合 ZIP 收入相同版本的可执行宿主 JAR、四个插件 JAR、配置样例与许可文件。薄包使用同一个宿主 JAR。插件版本保留 `0.0.1-early`，PF4J 描述符用必需依赖 ID，兼容性在装配中验证。

## Risks / Trade-offs

- Java 扩展运行在宿主 JVM。Agent 可调用工具的权限与运营者安装受信任 JAR 的权限分别校验。
- 文件工作区与 Bash 需要分别验证路径规范化、链接及读写约束。要求隔离的 Bash 验收包含沙箱可用和不可用两条路径。
- Search 启用后使用独立配置指定凭据引用。配置或日志中只保留引用。

## Migration Plan

先装配通用 PF4J 宿主与配置入口，再迁入 Workspace、Docs、Search、Bash。使用空宿主、手工安装和整合包验收。既有 JAR 旁的插件目录可通过显式 `plugins.dir` 使用。

## 用户裁定

### 迁移、打包与依赖（对应决策 2、5）

> 接下来从ProjectMosire迁移workspace、docs、search、bash到本项目，我认为这四个模块都应该使用PF4J的方式挂载到本项目，可以出一个统一打包的版本，把这4个插件都打包进去以解决一致性问题——哦对了，.agentmosire目录下的plugins目录放一般载入的插件，对于薄包，plugins下放这四个模块的对应jar也应当可以正确加载四个插件；依赖关系为docs和bash依赖workspace，因为bash提供的运行能力可以利用workspace进行鉴权判定，docs则是不必多说；这是否可行？设计工作流和给我展示理想的项目架构、功能

本轮范围接续首期三模块交付，首期范围原文保留在 `establish-agent-runtime/design.md`。

### 配置与共享工作区（对应决策 1、3、4）

> 对于四个插件，应当允许其在plugins目录下独立创建插件专属文件夹，其中四个插件分别载入自身所需配置，Main的插件加载层允许提供一套标准的插件配置载入样板；此外还有插件鉴权，workspace相当于是托管了一整套插件鉴权工具，允许为不同Agent在agent配置中挂载相同的只读工作区等，通过进一步的插件执行面限定，来实现共享目录等自定义操作，这需要注意；还有其他裁定和需要确认的设计点吗？理解设计，如果没问题，开始开发
