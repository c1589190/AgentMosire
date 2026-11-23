# Proposal

## Why

AgentMosire 的 Java 包和 Maven 坐标需要项目级命名空间。隔离的 DeepSeek 实测也复现了两处工具授权差异和两处决策包结果问题。

## What Changes

- 先统一 Java 包前缀和 Maven `groupId` 为 `io.github.c1589190.agentmosire`，发布版本为 `0.0.1-early2`。
- 落实 READONLY 点名授权和同层黑白名单互斥，修正 Bash 执行失败的审批回执及裁决前提交的 HTTP 状态。
- 校验全量制品、本地 Maven 消费和真实模型场景，再发布 GitHub 预发布。

## Capabilities

### Modified Capabilities

- `runtime-host`：项目命名空间、Maven 坐标及发布制品。
- `tool-authorization`：READONLY 点名授权、同层名单校验以及审批执行结果。

## Impact

本次变更涉及所有 Maven 模块。旧版调用方和 PF4J 插件需要更新依赖坐标和 Java 引用后重编。工具 ID、数据目录和工作区配置格式沿用现有定义。
