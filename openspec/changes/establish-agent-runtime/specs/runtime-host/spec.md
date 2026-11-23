# Spec Delta

## Purpose

提供可嵌入的 Java Agent 运行时和独立服务入口。Main 装载外部扩展并将其工具交给同一运行时，使插件部署与每个 Agent 的调用授权分别可验证。

## ADDED Requirements

### Requirement: 三模块产物和运行入口

首次交付 SHALL 提供 `AgentLib`、`Brain`、`Main` 三个可独立产出 JAR 的 Maven 模块。项目名为 `AgentMosire`，包前缀为 `io.github.c1589190`，版本为 `0.0.1-early`。外部 Java 项目可以嵌入 Brain，Main 使用该运行时提供独立服务。

#### Scenario: 从外部项目嵌入运行时

- **WHEN** 一个独立 Maven 项目只解析首期交付所需的运行时制品，并提供模型客户端和显式注册的 Java 工具
- **THEN** 它可以执行会话和获授权的工具调用

#### Scenario: 启动未安装插件的 Main

- **WHEN** 从当次构建产物启动 Main，且插件目录为空或未配置
- **THEN** 独立服务可以启动并运行基础对话

### Requirement: 装载标准外部插件

Main SHALL 统一加载符合宿主扩展契约的 PF4J 插件 JAR，按描述符处理版本和依赖，发布其工具来源，并在停用时移除对应来源。装载状态 SHALL 与 Agent 的实际调用授权独立。

#### Scenario: 加载独立构建的插件

- **WHEN** 运营者安装一个用新扩展 API 编译且依赖皆已满足的外部 JAR
- **THEN** Main 装载其工具来源，仅具备相应授权的 Agent 能直接调用该工具

#### Scenario: 文件名顺序与插件依赖顺序不同

- **WHEN** 外部插件声明对另一个已安装插件的依赖，且文件名排序把它排在依赖之前
- **THEN** Main 按依赖顺序装载，并登记有效插件的工具来源

#### Scenario: 插件停用

- **WHEN** 已装载插件停用
- **THEN** 后续工具目录与调用入口均移除该插件的工具，其他工具来源继续工作

#### Scenario: 加载失败

- **WHEN** 插件依赖缺失、扩展不兼容或工具名与现有来源冲突
- **THEN** Main 返回可定位的装载失败，该插件的工具不会部分生效
