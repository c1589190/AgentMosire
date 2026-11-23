# Proposal

## Why

从 `ProjectMosire` 提取标准的 Java Agent 运行时，需要建立一致的包命名、模块边界和架构记录。首期同时验证 Java 嵌入和独立宿主的工具权限、插件装载与父子决策协作。

## What Changes

- 以 `AgentMosire` 为项目名，采用 Java 21、Maven 和 `0.0.1-early`。首期交付 `AgentLib`、`Brain`、`Main` 三个模块及其本地 JAR。发布和分发流程后置。
- **BREAKING** Java 包前缀和 Maven `groupId` 统一为 `io.github.c1589190`。各模块内部子包路径按原结构迁移，明确改归其他模块的职责随之迁移。消费者和外部插件切换时使用新坐标并重新编译。
- `AgentLib` 定义消息等上下文元素。`Brain` 管理窗口、运行循环、可替换的压缩策略和上下文注入。嵌入式与服务路径共用 Brain。
- **BREAKING** 身份采用 `READONLY`、`DEFAULT`、`SYSTEM`。Agent 配置用 `Allow`、`Ask`、`Block` 表示工具权限，工具模块维护规则和资源语义，父级规定直调资源限制和越界处理。
- **BREAKING** 决策包由子级提交草案，获批调用由有权限的父 Agent 代为执行并回传结果。子级可在执行中查看草案、提问或提交，支持暂停等待与异步回复注入。
- Main 统一装载符合扩展契约的 PF4J 外部 JAR。BashPlugin、Workspace、Docs、Search 保留在原项目，首期以独立构建的外部插件验收宿主。

## Capabilities

### New Capabilities

- `agent-context` — Brain 拥有会话上下文，提供可替换的压缩行为和可控的回复注入。
- `tool-authorization` — 身份默认组、显式工具权限、资源限制和父子直调权限单调性。
- `parent-agent-coordination` — 执行中发起父级请求、决策包代执行、阻塞与异步回复。
- `runtime-host` — 三模块独立构建、共用运行时和 Main 的外部插件装载。

### Modified Capabilities

本项目首次建立规格。

## Impact

首批迁移基线为原仓提交 `df1b4202755127b4e86c29a73de7bd9944eed1e5`。迁移涉及 Maven 坐标、公开 Java 包、工具授权入口、父子进程请求与执行回执，以及 Main 的服务与插件装配。首期验收覆盖从独立项目嵌入 Brain、启动 Main、装载外部插件、直调授权和父子草案闭环。版本范围与裁定出处见 [架构设计与原话](design.md)。
