# Design

## Context

`0.0.1-early` 使用账号级 Java 包前缀和 Maven 坐标。真实 DeepSeek 场景显示，工具目录仍按最低身份过滤 READONLY 的点名白名单，矛盾的子 Agent 模板可装载，命令非零退出可被记为已执行成功，决策包未裁决时执行会返回服务器错误。

## Decisions

1. Java 包路径、POM 和模块边界检查同时使用 `io.github.c1589190.agentmosire`。各模块沿用原子包层级。干净构建重新生成 PF4J 索引、版本资源和整合包。
2. AgentLib 的共用授权判据认可 READONLY 对普通档工具的点名白名单。通配符仍使用身份默认门槛。SYSTEM 工具、只读锁、身份继承、角色、敏感及破坏许可、工具闸和工作区分别参与判定。父级的通配默认授权不能派生同档的点名覆盖。
3. Agent 配置和子 Agent 模板各自在读入时拒绝同层名单交集，诊断包含冲突工具。跨层追加拒绝由生效权限集处理，派生配置记录扣除被拒名称后的白名单。
4. 工具可以为已批准的执行返回具名失败。Bash 直接调用照常交付命令退出码和输出。决策包执行遇到非零退出时，回执记录失败和退出码，允许同包其他获批项继续执行。HTTP 执行口将 `PACKAGE_NOT_DECIDED` 映射为 `409 Conflict`。
5. 用 Maven 编译、制品检查、本地 SDK 消费以及隔离的真实 DeepSeek 场景检查发布提交。GitHub 预发布提供整合包和薄包所需 JAR。

## Risks / Trade-offs

旧坐标或旧 Java 包编译的 SDK 消费方和 PF4J 插件需要迁移后重编。模板装载器会拒绝已有的重叠配置。Bash 非零退出只能报告该次执行失败，已经产生的部分副作用仍可能存在。

## User Decisions

> groupid也改成io.github.c1589190.agentmosire吧，先改名再修复，发布为0.0.1-early2

本轮命名裁定更新 [首期命名记录](../establish-agent-runtime/design.md) 的发布坐标。此前关于工具授权的裁定继续适用。

> 白名单可覆盖 READONLY 的默认分组并允许直写

> 黑名单、白名单不可重合，因为同级
