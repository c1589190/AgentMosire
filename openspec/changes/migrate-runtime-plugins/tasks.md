# Tasks

## 1. 宿主

- [x] 1.1 让 Main 默认从数据根加载插件并提供每插件独立配置，验证空目录、独立配置和显式目录。
- [x] 1.2 实现 PF4J 依赖顺序、依赖服务与停用，验证逆序文件名、缺依赖和第三方扩展。

## 2. 工具插件

- [x] 2.1 迁移四模块及测试，核验包名、描述符、依赖边界和入口。
- [x] 2.2 接入 Agent 的 Workspace 挂载配置、父子范围和 Docs 读写边界，验证两个 Agent 共享只读目录。
- [x] 2.3 将 Bash 执行面的只读和可写根接到 Workspace，验证拒绝和进程隔离。
- [x] 2.4 让 Search 从专属配置读取启用项和凭据引用，验证离线请求和配置失败。

## 3. 打包和集成

- [x] 3.1 构建薄包、四个插件和同版整合 ZIP，补充配置和启动指南。
- [x] 3.2 比较实际 JAR 的两种安装方式并复跑 Maven 与模块边界校验。

## Verification

- Maven 测试共 1,781 项，1,774 项通过，7 项因直接启动环境缺少挂载权限跳过，无失败或错误。格式、静态分析和打包使用 `./mvnw -B -ntp -o -DskipTests verify` 通过。
- 在 Linux 用户命名空间运行 `BashWorkspaceAccessTest` 的两项检查通过，覆盖真实读入和只读挂载拒绝写入。普通环境验证了隔离不可用时的拒绝路径。
- 从同一次构建的 `Main/target/mosire.jar` 和 `Distribution/target/agentmosire-0.0.1-early-bundle.zip` 启动。空宿主可用，缺 Workspace 时报错，手工安装和整合包登记相同的四个插件和 15 个工具。比对了包内 JAR 的字节，并通过两个真实子 Agent 进程验证了共享读取与 Docs 写入拒绝，实际 Bash 插件也经隔离读取该目录。
- `python3 scripts/check-module-boundaries.py` 核对八个 Maven 模块的边界通过。
