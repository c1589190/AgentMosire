# Spec Delta

## Purpose

以身份默认值、工具三组和资源范围表达 Agent 权限。每个工具模块定义自身规则和资源语义，运行时统一判定，并约束父子链上的直调权限。

## ADDED Requirements

### Requirement: 身份档位与工具分组

系统 SHALL 使用 `READONLY`、`DEFAULT`、`SYSTEM` 三种身份档位。身份提供工具的默认分组。Agent 配置 SHALL 能用 `Allow`、`Ask`、`Block` 覆盖本 Agent 的默认分组，同一层的白名单与黑名单必须互斥。

#### Scenario: READONLY 白名单授予直写

- **GIVEN** 某写工具的 `READONLY` 默认分组不允许直调，父级授权覆盖该工具及目标资源
- **WHEN** `READONLY` Agent 用显式白名单将该工具归入 `Allow` 并调用它
- **THEN** 工具按该 Agent 的实际资源范围直接执行写入

#### Scenario: 同级显式配置冲突

- **WHEN** 同一工具同时出现在同一层的白名单和黑名单
- **THEN** 配置被拒绝，并返回该工具的冲突信息

#### Scenario: 工具已有 Ask 配置

- **WHEN** Agent 调用有效分组为 `Ask` 的工具
- **THEN** 记录一份调用草案，等待向父级提交，此时工具本体没有执行

#### Scenario: Block 禁止调用

- **WHEN** Agent 请求有效分组为 `Block` 的工具
- **THEN** 返回拒绝，调用没有进入草案且没有产生工具副作用

### Requirement: Allow 的资源约束

工具模块 SHALL 定义资源模型和具体调用的访问语义。运行时 SHALL 只对 `Allow` 分组应用该 Agent 的资源规则，并在副作用前按父级约束处理资源。父级可配置受限访问为拒绝或转入 `Ask`。

#### Scenario: 直调目标超出允许范围

- **GIVEN** 父级限定子 Agent 的工具直调范围为 `team/a`，并将其他目标配置为 `Ask`
- **WHEN** 子 Agent 调用该工具访问 `team/b`
- **THEN** 本次调用进入决策草案，子 Agent 没有直接访问目标

#### Scenario: 父级要求拒绝受限访问

- **WHEN** `Allow` 组的一次调用触碰父级明确配置为拒绝的资源
- **THEN** 运行时拒绝该调用并返回原因

### Requirement: 子级直调权限单调性

运行时 SHALL 校验每一级子 Agent 的有效直调工具与资源范围都在其直接父级的有效直调范围内。`Ask` 允许发起有授权的请求，父级代执行时重新校验自己的工具权限和资源范围。

#### Scenario: 孙级申请更大的直调范围

- **GIVEN** 中间一级 Agent 的直调范围仅覆盖 `team/a`
- **WHEN** 它尝试为下一层子 Agent 配置 `team/b` 的直调权限
- **THEN** 运行时拒绝扩大后的直调授权；经配置允许的 `Ask` 只生成草案
