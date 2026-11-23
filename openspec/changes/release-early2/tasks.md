# Tasks

## 1. 命名和制品

- [x] 1.1 迁移 Java 源集和资源路径、Maven 坐标、版本、装配配置、模块边界检查和当前使用说明。`./mvnw -B -ntp -o -DskipTests clean test-compile` 和模块边界检查通过。

## 2. 工具授权和决策包

- [x] 2.1 修复 READONLY 点名白名单与模板名单冲突。真实子 Agent 在可写挂载成功写入，只读挂载升级在启动前被拒。冲突模板退出码为 `1`，日志点名冲突工具。
- [x] 2.2 修复 Bash 命令失败的执行回执与决策包提交前置状态。实测 `409 / PACKAGE_NOT_DECIDED`、`NOT_COMMITTED / SHELL_NONZERO_EXIT` 及部分执行成功的逐项回执。

## 3. 交付

- [x] 3.1 `./mvnw -B -ntp -o -DskipTests install` 通过。整合包与薄包装载四个插件，启用 Search 后登记相同的 15 个插件工具。外部 Maven 示例通过新坐标运行。隔离复测覆盖 15 次场景和 34 次真实模型调用，结果保存在本地 `.work/`。
- [ ] 3.2 提交并推送发布提交，创建 `0.0.1-early2` GitHub 预发布并核对标签和制品。
