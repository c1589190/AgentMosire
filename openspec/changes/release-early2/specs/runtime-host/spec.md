# Spec Delta

## ADDED Requirements

### Requirement: AgentMosire 项目级命名空间

自 `0.0.1-early2` 起，所有运行时和插件制品 SHALL 使用 Java 包前缀和 Maven `groupId` `io.github.c1589190.agentmosire`。发布的宿主和内置插件 SHALL 使用同一版本。

#### Scenario: 以新坐标构建和装载

- **WHEN** 消费方用新坐标依赖 Brain，或用同版本的宿主装载任意内置插件
- **THEN** 消费方可引用新包路径且宿主可发现各插件扩展和声明的依赖
