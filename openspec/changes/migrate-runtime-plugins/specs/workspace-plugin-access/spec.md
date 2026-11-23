# Spec Delta

## Purpose

让多个 Agent 安全共享同一工作区资源，通过独立挂载和统一的调用者鉴权向文档、Shell 及后续插件提供可复用的访问边界。

## ADDED Requirements

### Requirement: 为每个 Agent 独立挂载

Workspace SHALL 依据 Agent 配置建立实例级挂载视图。不同实例可以引用同一物理目录，子级的直接访问范围 SHALL 受其上级范围约束。

#### Scenario: 两个实例共享只读目录

- **WHEN** 两个获授权的 Agent 为同一目录配置只读挂载
- **THEN** 两者均可以读自己的挂载，任一挂载的卸载不改变另一个挂载，两者均无法经该挂载写入

### Requirement: 插件执行保持挂载边界

Docs、Bash SHALL 通过同一个 Workspace 服务根据可信的调用者身份解析路径。读写权限 SHALL 同时满足挂载模式和工具执行面的限制。

#### Scenario: 只读目录上的文档写入或 Shell 写入

- **WHEN** 已获工具调用资格的 Agent 尝试在只读共享挂载中写入
- **THEN** Docs 拒绝写入，Bash 的隔离范围保持该挂载只读，宿主目录内容保持不变

#### Scenario: Bash 所需的隔离不可用

- **WHEN** 受工作区约束的 Bash 调用无法建立隔离
- **THEN** 返回明确失败且没有启动目标命令
