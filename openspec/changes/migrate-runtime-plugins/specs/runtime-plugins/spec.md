# Spec Delta

## Purpose

提供统一的插件发现、初始化和配置约定，使手工部署与本地整合包使用相同的插件制品和权限入口，并允许操作者按插件独立配置和安装工具。

## ADDED Requirements

### Requirement: 统一插件发现和依赖顺序

Main SHALL 默认发现 `<data-dir>/plugins` 中的独立 JAR，检查宿主兼容性和必需依赖，并按依赖顺序初始化和登记工具。

#### Scenario: 文件名排在依赖前

- **WHEN** Docs 或 Bash JAR 排在 Workspace JAR 前
- **THEN** Workspace 先初始化，四个有效插件均可正常加载

#### Scenario: 缺少 Workspace

- **WHEN** 加载 Docs 或 Bash 时 Workspace 未安装
- **THEN** 启动诊断指出缺少的 `workspace`，相依工具不进入目录

### Requirement: 独立配置与目录

宿主 SHALL 为每个已装配插件提供由其 ID 命名的目录和配置对象。插件 SHALL 校验自己的字段并可在专属目录中保存数据。

#### Scenario: 两个插件使用相同字段名

- **WHEN** 两个插件的专属 `config.json` 含同名键
- **THEN** 每个插件只收到自己目录的配置，同时根目录的 JAR 发现正常

### Requirement: 同版制品打包

构建 SHALL 提供无业务插件的可执行宿主、四个独立插件 JAR，以及由这些制品组成的整合 ZIP。

#### Scenario: 手工安装与整合包

- **WHEN** 同一次构建的四个插件被手工复制到薄包的数据目录，或从整合 ZIP 启动
- **THEN** 相同配置下得到相同的插件版本和工具目录
