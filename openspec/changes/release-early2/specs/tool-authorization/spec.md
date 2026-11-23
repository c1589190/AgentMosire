# Spec Delta

## ADDED Requirements

### Requirement: 点名白名单和同层配置校验

READONLY 的点名白名单 SHALL 覆盖普通档工具的身份默认判定。通配符 SHALL 使用身份默认判定。配置层 SHALL 拒绝同时允许和拒绝的同一个工具名。父级授权仍是子级的上限。

#### Scenario: 点名授权后在可写工作区创建文档

- **GIVEN** READONLY 子 Agent 由授权父级放行 `doc_create` 并获得可写挂载和所需操作许可
- **WHEN** 它发起该调用
- **THEN** 工具可见且文件按工作区约束创建

#### Scenario: 黑白名单重叠

- **WHEN** 模板同时允许和拒绝 `doc_read`
- **THEN** 模板装载失败，诊断指出 `doc_read`

### Requirement: 决策包执行结果准确

未裁决的决策包执行请求 SHALL 返回 `409 Conflict` 和 `PACKAGE_NOT_DECIDED`。Bash 已批准命令非零退出 SHALL 生成失败的条目回执。

#### Scenario: 审批通过后命令写只读目录失败

- **WHEN** 人工批准并提交一个在只读挂载中创建文件的命令
- **THEN** 文件未创建，条目回执包含失败退出码
