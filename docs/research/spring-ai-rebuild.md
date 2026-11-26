# 基于 Spring AI 重构 AgentMosire 的前期调查

调查日期为 2026-11-26。旧版基线为相邻 `AgentMosire-old` 的 `0.0.1-early3` 工作树，HEAD 为 `3645bf2`，含未提交改动。源码引用链接指向该提交。以下结论依据旧版源码、已有测试和上游正式资料。架构与场景是候选方案，运行验证尚待完成。旧项目的 OpenSpec 主规格为空，相关契约记录在各变更的 `specs/` 中。本文供当前项目起草第一份正式 OpenSpec 变更使用。 [旧版 README](https://github.com/c1589190/AgentMosire/blob/3645bf291e23feda2c43d04620e0a13a504b491e/README.md) · [旧版运行时变更](https://github.com/c1589190/AgentMosire/blob/3645bf291e23feda2c43d04620e0a13a504b491e/openspec/changes/establish-agent-runtime/proposal.md) · [旧版 early2 权限规格](https://github.com/c1589190/AgentMosire/blob/3645bf291e23feda2c43d04620e0a13a504b491e/openspec/changes/release-early2/specs/tool-authorization/spec.md)

## Why

AgentMosire 有两类独立消费者。工具作者需要把已有业务方法包装一次，供 Java 宿主、程序内 Agent 和 MCP 客户端复用。Agent 宿主需要组合多个工具包，在程序内创建、交互和关闭 Agent 实例，并由宿主控制模型、会话、权限和审批渠道。旧版 `AgentLib` 同时携带工具、鉴权、MCP、模型客户端、存储与 PF4J，`Brain` 承担模型循环和决策流程，`Main` 再装配各种接入面。拆出两类外部入口，可以让工具作者和 Agent 宿主分别引入自己需要的依赖。 [旧版 AgentLib POM](https://github.com/c1589190/AgentMosire/blob/3645bf291e23feda2c43d04620e0a13a504b491e/AgentLib/pom.xml) · [旧版 Brain POM](https://github.com/c1589190/AgentMosire/blob/3645bf291e23feda2c43d04620e0a13a504b491e/Brain/pom.xml) · [旧版 Java 嵌入说明](https://github.com/c1589190/AgentMosire/blob/3645bf291e23feda2c43d04620e0a13a504b491e/README.md#java-嵌入)

## What Changes

建议用 **Spring AI 2.1.0、Spring Boot 4.2.0、JDK 21** 做首轮验证。Spring AI 2.1.0 于 2026-11-20 正式发布，使用 MCP Java SDK 2.2.0，并提供模型适配、工具回调与调用循环、MCP 客户端和服务端、Session API 等基础设施。Spring Boot 集中在可选宿主装配层。AgentMosire 保留工具授权、宿主内 Agent 装配和决策包领域语义。官方发布公告把多 Agent 编排项目 Spring AI Agent 列为后续孵化项目，首轮以已发布 API 为基础。 [Spring AI 2.1.0 发布公告](https://spring.io/blog/2026/11/20/spring-ai-2-1-0-GA-available-now/) · [Spring Boot 4.2 系统要求](https://docs.spring.io/spring-boot/4.2/system-requirements.html)

## Context

### 旧版行为基线

| 能力 | 从源码和规格读出的行为 |
| --- | --- |
| 工具与来源 | `AgentTool` 提供输入 Schema、工具规格、资源面和执行方法。`ToolRegistry` 汇总本地、插件和 MCP 来源。`PluginToolSource` 管理 JAR 生命周期，并可向具体 Agent 附加插件资源范围。旧版支持运行中开关插件与目录变化。 [AgentTool](https://github.com/c1589190/AgentMosire/blob/3645bf291e23feda2c43d04620e0a13a504b491e/AgentLib/src/main/java/io/github/c1589190/agentmosire/agentlib/tool/AgentTool.java) · [插件规格](https://github.com/c1589190/AgentMosire/blob/3645bf291e23feda2c43d04620e0a13a504b491e/openspec/changes/migrate-runtime-plugins/specs/runtime-plugins/spec.md) |
| 授权 | 工具名、角色和资源范围先判定，再由工具闸位给出 `Allow`、`Block` 或 `Ask`。硬拒与越权在审批之前终止。点名授权、通配符、拒绝和父级上限有明确规则。MCP 外发还要通过 `noExport` 和注册过滤。 [ToolCallAuthorizer](https://github.com/c1589190/AgentMosire/blob/3645bf291e23feda2c43d04620e0a13a504b491e/AgentLib/src/main/java/io/github/c1589190/agentmosire/agentlib/tool/ToolCallAuthorizer.java) · [工具权限规格](https://github.com/c1589190/AgentMosire/blob/3645bf291e23feda2c43d04620e0a13a504b491e/openspec/changes/establish-agent-runtime/specs/tool-authorization/spec.md) · [MCP 外发实现](https://github.com/c1589190/AgentMosire/blob/3645bf291e23feda2c43d04620e0a13a504b491e/AgentLib/src/main/java/io/github/c1589190/agentmosire/agentlib/mcp/AgentToMcpServer.java) |
| 回合和决策包 | 装配批量审批时，`Ask` 登记本轮待裁决条目并返回 `DECISION_QUEUED`，模型可以继续本轮。收尾聚合非空队列，旧版也有当场单项审批入口。包支持草案内容版本、整包或逐条裁决、只提交获批集合、过期、冲突和统一回执。 [AgentPipeline](https://github.com/c1589190/AgentMosire/blob/3645bf291e23feda2c43d04620e0a13a504b491e/Brain/src/main/java/io/github/c1589190/agentmosire/brain/runtime/AgentPipeline.java) · [DecisionPackageTransitions](https://github.com/c1589190/AgentMosire/blob/3645bf291e23feda2c43d04620e0a13a504b491e/AgentLib/src/main/java/io/github/c1589190/agentmosire/agentlib/decision/DecisionPackageTransitions.java) · [DecisionPackages](https://github.com/c1589190/AgentMosire/blob/3645bf291e23feda2c43d04620e0a13a504b491e/AgentLib/src/main/java/io/github/c1589190/agentmosire/agentlib/decision/DecisionPackages.java) |
| 父子协作 | 规格包含子 Agent 向直接父级提问、交草案、等待或异步接收结果。父级代执行时使用父级自己的权限。旧实现另有一条跨进程审批回路，由子侧提交并回填裁决，通过受绑定的链路兑现一次性放行。这些路径的执行者和授权依据需要在迁移中逐一对应。 [父子协作规格](https://github.com/c1589190/AgentMosire/blob/3645bf291e23feda2c43d04620e0a13a504b491e/openspec/changes/establish-agent-runtime/specs/parent-agent-coordination/spec.md) · [跨进程提交实现](https://github.com/c1589190/AgentMosire/blob/3645bf291e23feda2c43d04620e0a13a504b491e/Brain/src/main/java/io/github/c1589190/agentmosire/brain/decision/CrossProcessDecisionSubmitter.java) · [父侧账本](https://github.com/c1589190/AgentMosire/blob/3645bf291e23feda2c43d04620e0a13a504b491e/Brain/src/main/java/io/github/c1589190/agentmosire/brain/decision/ParentSubagentDecisionLedger.java) |
| 存储与副作用 | 包登记、待决队列和父侧放行账本存在进程内。宿主可以把会话及审计事件存入 SQLite，两者承担不同职责。`DecisionCommitPort` 定义按 `packageId` 提交和对账的边界，`LocalDecisionCommitPort` 逐项调用本机工具并如实记录部分成功和失败，工具已经产生的副作用可能无法回滚。 [包终态说明](https://github.com/c1589190/AgentMosire/blob/3645bf291e23feda2c43d04620e0a13a504b491e/AgentLib/src/main/java/io/github/c1589190/agentmosire/agentlib/decision/PackageState.java) · [RuntimeStorage](https://github.com/c1589190/AgentMosire/blob/3645bf291e23feda2c43d04620e0a13a504b491e/Brain/src/main/java/io/github/c1589190/agentmosire/brain/runtime/RuntimeStorage.java) · [提交口](https://github.com/c1589190/AgentMosire/blob/3645bf291e23feda2c43d04620e0a13a504b491e/AgentLib/src/main/java/io/github/c1589190/agentmosire/agentlib/decision/DecisionCommitPort.java) · [本机提交实现](https://github.com/c1589190/AgentMosire/blob/3645bf291e23feda2c43d04620e0a13a504b491e/Main/src/main/java/io/github/c1589190/agentmosire/main/decision/LocalDecisionCommitPort.java) |

### Spring AI 能力对照

| 环节 | 采用的上游能力 | AgentMosire 的责任 |
| --- | --- | --- |
| 工具定义与调用 | `@Tool`、`ToolCallback`、`ToolCallbackProvider` 提供方法包装、Schema 和调用适配。 | 提供可复用的工具声明和受控执行入口。宿主已有的 `@Tool` 只有显式注册并绑定授权策略后才纳入目录。 [Spring AI Tool Calling](https://docs.spring.io/spring-ai/reference/api/tools.html) |
| 模型与循环 | `ChatClient`、`ChatModel`、`ToolCallingAdvisor` 提供模型适配和调用循环，2.1 的有序 `MessagePart` 支持工具、推理和多模态内容。 | 管理每个 Agent 的工具集、回合状态、终止条件和决策包。真实模型回放验证 DeepSeek 推理字段、工具调用 ID、图片和 token 用量。 [ToolCallingAdvisor](https://docs.spring.io/spring-ai/reference/api/tools/tool-calling-advisor.html) · [Spring AI 2.1 消息模型](https://spring.io/blog/2026/11/20/spring-ai-2-1-0-GA-available-now/) |
| 会话记忆 | 2.1 Session API 提供会话日志、会话 Advisor、压缩和 JDBC Repository。 | 宿主约束同一会话的并发，决定保留窗口、历史访问权限、父级回复注入和存储迁移。官方 JDBC 方言未列 SQLite。 [Session Advisor](https://docs.spring.io/spring-ai/reference/api/session/chat-client.html) · [JDBC Repository](https://docs.spring.io/spring-ai/reference/api/session/jdbc.html) |
| MCP 传输 | Server Starter 和 Java SDK 支持 STDIO、Streamable HTTP 及新版协议，Client Starter 可管理多条连接、名称和目录变化。 | 定义哪些工具可公开，如何传递经过验证的身份，怎样按身份列举和再次授权，以及如何映射结果和审批。 [Server Starter](https://docs.spring.io/spring-ai/reference/api/mcp/mcp-server-boot-starter-docs.html) · [Client Starter](https://docs.spring.io/spring-ai/reference/api/mcp/mcp-client-boot-starter-docs.html) |
| 单轮工具确认 | `ToolCallConfirmationPolicy` 选中一条工具调用时，该次模型回复里的整组调用会先暂停。宿主收集裁决，用 `ToolCallingManager` 回放该组调用并继续对话。 | 批量决策沿用跨多次模型回复收集 `Ask`、到回合边界组包、提交后生成回执的语义。单次回复级确认可供独立工具使用场景评估。 [Tool Call Confirmation](https://docs.spring.io/spring-ai/reference/api/tools/tool-call-confirmation.html) |
| 模型辅助裁决 | `DecisionModel` 返回类型化的判断、分类、评分和置信度。 | 如需要，可接入为裁决建议来源。谁有权裁决、批准绑定哪一版参数、如何提交并生成回执，均由决策包规则确定。 [Spring AI Decision Models](https://spring.io/blog/2026/11/20/spring-ai-2-1-0-GA-available-now/#decision-models) |

## Decisions

以下为首轮验证建议。

### D1. 独立工具库和可嵌入 Agent 运行库

| 候选制品 | 提供的入口与依赖边界 |
| --- | --- |
| `agent-tool` | Java 工具定义、模块贡献、受控调用、身份与授权策略接口、结构化工具结果。普通 Maven 依赖可用，公开契约使用 Java 类型和 JSON 结构。 |
| `agent-tool-spring-ai` | 把受控调用适配到 `ToolCallback`，允许显式收录宿主已有回调。依赖 `agent-tool` 和 Spring AI 工具 API，可程序化组装。 |
| `agent-tool-mcp` | 把同一批受控工具选择性发布为 MCP，也可把远端 MCP 工具纳入目录。优先用 Spring AI 和 Java SDK 的服务端、客户端及传输。Boot 装配做成可选入口。 |
| `agent-runtime` | 可嵌入的 Agent 工厂和实例生命周期。组合多个工具模块及可选 MCP 来源，提供会话、请求、流式结果、取消与关闭。依赖 Spring AI 框架能力及工具适配。 |
| 决策包领域代码 | 工具执行层提供 `Ask` 判定及待裁决登记接口。由 Agent 运行库组合决策包逻辑。需要独立提交包的外部宿主出现时，再验证单独发布制品。 |

包通过显式模块列表装配，可从普通 Maven 依赖取得。Spring 宿主可将模块 Bean 注入同一个工厂。实例持有自己的工具选择、权限、配额、模型配置和会话标识。权限使用稳定工具标识，模型工具名和 MCP 名称由适配层按来源映射，装配时检测冲突。共享模型客户端与工具模块由其创建者关闭，Agent 关闭时释放自己的会话和订阅。旧版动态目录若需兼容，保留 PF4J 为可选来源，验证装载、禁用和释放资源。 [旧版 AgentRuntime Builder](https://github.com/c1589190/AgentMosire/blob/3645bf291e23feda2c43d04620e0a13a504b491e/Brain/src/main/java/io/github/c1589190/agentmosire/brain/runtime/AgentRuntime.java) · [旧版插件契约](https://github.com/c1589190/AgentMosire/blob/3645bf291e23feda2c43d04620e0a13a504b491e/openspec/changes/migrate-runtime-plugins/specs/runtime-plugins/spec.md)

```text
Java host --> AgentRuntime --> ChatClient / ToolCallingAdvisor
                                     |
                               ToolCallback adapter
                                     |
Java caller ----------------> AgentTool invocation <--- MCP adapter <--- MCP client
                                  |        |
                             tool module   +--> decision package --> approval / commit
```

### D2. 各入口使用同一套授权，MCP 显式导出

本地 Java 调用、Spring AI 回调、MCP 调用和获批提交都由宿主绑定可信调用上下文，然后进入同一套权限和资源校验。宿主可配置身份、角色和资源政策，工具作者声明工具及其资源需求。普通调用根据 `Allow`、`Block`、`Ask` 分流。提交入口携带已记录裁决和执行身份，只能兑现对应内容版本、条目、规范化参数摘要和权限范围，执行时重查工具可用性、硬拒与资源边界。需要线程切换时显式传递回合和身份，覆盖同步、流式和取消路径。进程内工具包属于宿主信任边界，隔离未知来源的可执行包需要另设进程边界与最小权限。 [旧版 ToolCallAuthorizer](https://github.com/c1589190/AgentMosire/blob/3645bf291e23feda2c43d04620e0a13a504b491e/AgentLib/src/main/java/io/github/c1589190/agentmosire/agentlib/tool/ToolCallAuthorizer.java) · [旧版授权回归规格](https://github.com/c1589190/AgentMosire/blob/3645bf291e23feda2c43d04620e0a13a504b491e/openspec/changes/release-early2/specs/tool-authorization/spec.md)

MCP 启用后，先从显式导出清单和工具 `noExport` 求得服务面，再根据已认证主体过滤目录，执行时重新判权。需要多身份的服务先验证 SDK 的 `tools/list` 和 `tools/call` 能否取得同一可信身份。若列表无法按请求过滤，首轮可按固定调用身份和权限集合发布独立 MCP 服务实例。上游 Starter 的 HTTP 端点需要单独配置安全层。建议用 Spring Security 验证凭据并建立主体，按部署约定处理 TLS、Origin 与 OAuth 资源发现。STDIO 由宿主绑定启动身份。协议元数据、模型参数和会话 ID 均只作为请求数据。 [Server Starter 安全与上下文](https://docs.spring.io/spring-ai/reference/api/mcp/mcp-server-boot-starter-docs.html#_securing_the_mcp_server) · [Spring Security OAuth Resource Server](https://docs.spring.io/spring-security/reference/servlet/oauth2/resource-server/index.html) · [MCP Authorization](https://modelcontextprotocol.io/specification/2026-07-28/basic/authorization)

MCP 优先验证程序化工具规格和 `CallToolResult` 映射。候选装配关闭 Starter 的通用 ToolCallback 自动转换和 MCP 注解扫描，由受控目录注册工具。Spring AI v2.1.0 的通用同步转换器把回调返回的文本标为 `isError=false`，异常走错误路径，图片使用单独分支。旧版结果码、图片和 `DECISION_QUEUED` 需要显式映射，验证业务拒绝和排队结果经过 MCP 编解码后仍可区分。导入远端工具时，保留来源和认证范围，只有经本地策略选择的回调才交给模型。首轮用旧协议 STDIO 或 `STREAMABLE` 验证旧客户端互通，再根据消费方的支持配置和测试 `MODERN`。 [Streamable HTTP Starter 配置](https://docs.spring.io/spring-ai/reference/api/mcp/mcp-streamable-http-server-boot-starter-docs.html) · [v2.1.0 MCP 回调转换源码](https://github.com/spring-projects/spring-ai/blob/v2.1.0/mcp/common/src/main/java/org/springframework/ai/mcp/McpToolUtils.java) · [MCP 2026-07-28 说明](https://blog.modelcontextprotocol.io/posts/2026-07-28/) · [旧版 MCP 结果码](https://github.com/c1589190/AgentMosire/blob/3645bf291e23feda2c43d04620e0a13a504b491e/AgentLib/src/main/java/io/github/c1589190/agentmosire/agentlib/mcp/McpWireCode.java)

### D3. 保留决策包状态机，优先让 Spring AI 驱动模型循环

建议第一版用 `ChatClient` 和 `ToolCallingAdvisor` 跑模型循环，所有回调调用受控入口。在 `Ask` 时将条目登记到宿主本轮队列，返回带 `DECISION_QUEUED` 的工具结果。回合结束按旧版语义汇总，宿主提交一次裁决请求。需要支持运行中主动提交时，由 Agent 运行时封存当前草案，后续 `Ask` 归入新的草案。回合与草案由 Agent 运行时持有，`ToolContext` 负责传递宿主提供的引用。批量流程只选一套审批执行权，Spring AI 的回复级确认单独装配。 [ToolCallingAdvisor 扩展点](https://docs.spring.io/spring-ai/reference/api/tools/tool-calling-advisor.html) · [旧版 PendingDecisionQueue](https://github.com/c1589190/AgentMosire/blob/3645bf291e23feda2c43d04620e0a13a504b491e/AgentLib/src/main/java/io/github/c1589190/agentmosire/agentlib/decision/PendingDecisionQueue.java) · [旧版父子协作规格](https://github.com/c1589190/AgentMosire/blob/3645bf291e23feda2c43d04620e0a13a504b491e/openspec/changes/establish-agent-runtime/specs/parent-agent-coordination/spec.md)

如果 Advisor 的时序无法保留工具结果注入、流式取消或回合终止条件，先验证自定义 Tool Advisor。只有扩展点仍无法满足契约时，由宿主用 `ChatModel` 或关闭自动工具循环的 `ChatClient` 驱动必要的回合编排，继续复用上游模型、消息和工具接口。独立 MCP 调用没有 Agent 回合，由宿主显式选择单项确认、指定批次或拒绝 `Ask`。 [Spring AI ChatModel Tool Calling](https://docs.spring.io/spring-ai/reference/api/tools/chatmodel-tool-calling.html) · [旧版 DecisionApprovalBridge](https://github.com/c1589190/AgentMosire/blob/3645bf291e23feda2c43d04620e0a13a504b491e/Brain/src/main/java/io/github/c1589190/agentmosire/brain/decision/DecisionApprovalBridge.java)

领域逻辑优先复用旧版快照、状态转换、条目裁决、提交计划和回执。审批渠道作为宿主插件。包版本用于拒绝过期裁决，包 ID 用于重试和对账，跨进程放行绑定主体和具体条目且只能兑现一次。审计事件保留身份、包 ID、工具及条目结果等必要字段，在途审批视图受权限控制。决定首期支持重启恢复时，需要持久化决策包和执行进度，并约定宿主提交口的原子性、幂等边界和故障后的查询方式。事务型宿主可用一次提交处理获批集合；通用 Java/MCP 工具按条目记账，返回已执行、失败和结果未知等实际状态。发生超时而远端结果未知时，恢复路径应先查询或对账。 [旧版状态转换](https://github.com/c1589190/AgentMosire/blob/3645bf291e23feda2c43d04620e0a13a504b491e/AgentLib/src/main/java/io/github/c1589190/agentmosire/agentlib/decision/DecisionPackageTransitions.java) · [旧版提交接口](https://github.com/c1589190/AgentMosire/blob/3645bf291e23feda2c43d04620e0a13a504b491e/AgentLib/src/main/java/io/github/c1589190/agentmosire/agentlib/decision/DecisionCommitPort.java) · [旧版本机提交回执](https://github.com/c1589190/AgentMosire/blob/3645bf291e23feda2c43d04620e0a13a504b491e/Main/src/main/java/io/github/c1589190/agentmosire/main/decision/LocalDecisionCommitPort.java)

### D4. 会话与部署按兼容性验证推进

先用 Spring AI Session API 的内存实现验证模型历史、工具调用和压缩，再确定存储。旧 SQLite 会话可评估迁移或通过 `SessionRepository` 适配；采用官方 JDBC 实现时可选其已支持的数据库。每个会话串行处理输入和恢复，父级异步回复保留请求与会话关联，并验证模型失败后重投、成功后只消费一次。用宿主现有的审计读取口承接决策和执行记录。 [Spring AI Session 集成](https://docs.spring.io/spring-ai/reference/api/session/chat-client.html) · [Spring AI JDBC Repository](https://docs.spring.io/spring-ai/reference/api/session/jdbc.html) · [旧版 Agent 上下文规格](https://github.com/c1589190/AgentMosire/blob/3645bf291e23feda2c43d04620e0a13a504b491e/openspec/changes/establish-agent-runtime/specs/agent-context/spec.md)

旧版 Maven 使用 MCP SDK 2.1.0 和 Jackson 2，Spring AI 2.1 使用 MCP SDK 2.2.0，Spring Boot 4.2 默认 Jackson 3。先用上游 BOM 构建一个 Boot 消费方和一个普通 Maven 消费方，核对解析出的 SDK、Jackson、Reactor、日志依赖和 JDK 21 下的链接行为，再考虑复用依赖旧类型的代码。工具公共接口采用普通 Java 值与 JSON；旧插件若需要二进制兼容，单独验证类加载和公共类型。 [旧版父 POM](https://github.com/c1589190/AgentMosire/blob/3645bf291e23feda2c43d04620e0a13a504b491e/pom.xml) · [Spring Boot JSON 使用说明](https://docs.spring.io/spring-boot/4.2/reference/features/json.html) · [Spring AI 发布公告](https://spring.io/blog/2026/11/20/spring-ai-2-1-0-GA-available-now/)

## Risks / Trade-offs

| 选择 | 可避免的维护工作 | 进入实施前需要的证据 |
| --- | --- | --- |
| Spring AI Advisor 驱动循环 | 自维护模型往返、工具调用编解码、流式聚合和基础观测。 | 多次模型回复中的 `Ask` 可积累。普通调用、取消、预算耗尽和错误收尾均有明确结果。 |
| Spring AI / SDK 提供 MCP 传输和连接 | 自维护 JDK HTTP MCP 传输、连接初始化和协议细节。 | 身份级目录过滤、禁外发、目录变化、旧客户端和结构化结果通过互通测试。存在缺口时保留薄的注册、上下文和结果适配。 |
| Session API 管理模型历史 | 自维护通用会话读写、窗口、压缩与回忆。 | 明确旧 SQLite 数据是否迁移，验证上下文注入与工具结果顺序。决策包在持久化恢复前需要独立的状态和执行账本验证。 |
| Maven 模块显式装配 | 宿主无需操作插件目录便能消费多个工具包。 | 确认是否需要运行中增删 JAR，以及旧 PF4J 包的二进制兼容范围。 |

## Acceptance Scenarios

以下场景可转写到 `tool-authorization`、`mcp-tool-publishing`、`runtime-host` 和 `decision-packages` 的正式规格。旧测试为回归证据索引，场景尚未执行。

### Requirement: 工具可独立复用并受同一策略约束

三种入口 SHALL 使用一致的身份、工具与资源判定。MCP 目录 SHALL 只展示允许导出且当前主体可见的工具。

#### Scenario: 同一工具从进程内和 MCP 调用

- **GIVEN** 工具包只提供一个工具实现，宿主允许主体 A 读取指定资源集，主体 B 无权读取
- **WHEN** 两个主体通过 Java、Agent 和 MCP 调用，并分别查询 MCP 目录
- **THEN** A 只读取授权范围内的资源，B 的调用被拒且 MCP 目录隐藏该工具。标记 `noExport` 后，两个主体均无法通过 MCP 发现或调用该工具，A 仍可在进程内执行授权调用。 [旧版 MCP 权限测试](https://github.com/c1589190/AgentMosire/blob/3645bf291e23feda2c43d04620e0a13a504b491e/AgentLib/src/test/java/io/github/c1589190/agentmosire/agentlib/mcp/AgentToMcpServerAuthorityTest.java) · [禁外发测试](https://github.com/c1589190/AgentMosire/blob/3645bf291e23feda2c43d04620e0a13a504b491e/AgentLib/src/test/java/io/github/c1589190/agentmosire/agentlib/mcp/AgentToMcpServerNoExportTest.java)

### Requirement: 外部宿主可组合多个工具包并管理 Agent 实例

运行库 SHALL 接受多个工具模块，隔离实例的会话、身份和工具范围，并清楚报告装配冲突。

#### Scenario: 两个模块和两个 Agent 实例

- **GIVEN** 独立 Maven 消费方引入两个工具包，并为两个 Agent 配置不同权限
- **WHEN** 两个 Agent 并行对话，随后关闭其中一个
- **THEN** 每个 Agent 只看到各自允许的工具和历史，另一个实例可以继续运行。重复工具标识在装配时明确失败。 [旧版嵌入入口测试](https://github.com/c1589190/AgentMosire/blob/3645bf291e23feda2c43d04620e0a13a504b491e/Brain/src/test/java/io/github/c1589190/agentmosire/brain/runtime/AgentRuntimeEmbeddingTest.java) · [旧版生命周期测试](https://github.com/c1589190/AgentMosire/blob/3645bf291e23feda2c43d04620e0a13a504b491e/Brain/src/test/java/io/github/c1589190/agentmosire/brain/runtime/AgentRuntimeLifecycleModelTest.java)

### Requirement: 待裁决调用按批提交并给出可对账回执

运行时 SHALL 让 `Ask` 条目进入草案，按回合或显式提交边界封包，并仅在有效裁决和执行权限成立时调用获批条目。

#### Scenario: 一个回合跨两次模型回复收集调用

- **GIVEN** 固定响应模型跨两次回复请求两个 `Ask` 调用，并请求一个普通工具和一个硬拒工具
- **WHEN** Agent 完成本轮，审批人只批准其中一个 `Ask` 条目，提交方随后重复提交同一个包
- **THEN** 普通工具按策略执行，两个 `Ask` 在审批前的副作用均为零，硬拒条目不入包。只有获批且仍有效的条目执行一次，每次提交返回同一份逐条回执。 [旧版决策包状态测试](https://github.com/c1589190/AgentMosire/blob/3645bf291e23feda2c43d04620e0a13a504b491e/AgentLib/src/test/java/io/github/c1589190/agentmosire/agentlib/decision/DecisionPackageTransitionsTest.java) · [旧版宿主决策流程测试](https://github.com/c1589190/AgentMosire/blob/3645bf291e23feda2c43d04620e0a13a504b491e/Main/src/test/java/io/github/c1589190/agentmosire/main/decision/AppDecisionPackageFlowTest.java)

#### Scenario: 裁决基于旧版草案或执行权限已撤销

- **GIVEN** 审批人看到带内容版本的待裁决草案
- **WHEN** 草案编辑后提交旧版裁决，或有效裁决之后、执行之前撤销获批条目的权限
- **THEN** 旧版裁决在状态更新前被拒绝。权限已撤销的条目在提交时报告拒绝，工具体未执行。 [旧版决策包状态测试](https://github.com/c1589190/AgentMosire/blob/3645bf291e23feda2c43d04620e0a13a504b491e/AgentLib/src/test/java/io/github/c1589190/agentmosire/agentlib/decision/DecisionPackageTransitionsTest.java) · [旧版提交授权测试](https://github.com/c1589190/AgentMosire/blob/3645bf291e23feda2c43d04620e0a13a504b491e/Main/src/test/java/io/github/c1589190/agentmosire/main/decision/LocalDecisionCommitPortTest.java)

#### Scenario: 父子请求重试和结果回投

- **GIVEN** 子 Agent 通过绑定身份的连接提交可部分批准的请求
- **WHEN** 请求重复送达、审批到期、回复投递遇到模型失败，或一次性放行被重复使用
- **THEN** 各路径分别给出已受理、过期、等待投递或放行已使用的结果。成功的执行只记一次，父级回复只进入关联会话。 [旧版子 Agent 跨进程测试](https://github.com/c1589190/AgentMosire/blob/3645bf291e23feda2c43d04620e0a13a504b491e/Main/src/test/java/io/github/c1589190/agentmosire/main/app/ChildCrossProcessDecisionE2ETest.java) · [旧版父子协调规格](https://github.com/c1589190/AgentMosire/blob/3645bf291e23feda2c43d04620e0a13a504b491e/openspec/changes/establish-agent-runtime/specs/parent-agent-coordination/spec.md)

## Validation Plan

1. **验证循环。** 用固定响应模型、两个无外部副作用工具包和可计数的内存执行器，比较 Advisor 路线能否通过上述回合测试。同步和流式各覆盖一次，记录裁决前副作用计数、工具响应顺序、取消与超时。只有出现无法适配的时序，才加测宿主控制循环。
2. **验证发布和消费。** 由 Spring Boot 消费方发布受控工具，普通 Maven 消费方用工具库和 Agent 库调用。以 STDIO 或 Streamable HTTP 跑 MCP 互通，检查目录按身份变化、撤权即时生效、结果码和图片可往返，以及关闭一个 Agent 时共享资源的所有权。保留两种消费方的有效 POM 和依赖树作为依据。
3. **验证决策边界。** 回归整包裁决、逐条裁决、陈旧内容版本、部分失败、过期、同包并发重试及父子放行。记录审批、决策包、执行回执和审计各自的状态。若首期纳入恢复，补齐提交中断及外部结果未知场景。
4. **验证真实模型和存储。** 用模拟服务检查工具名、调用 ID、消息顺序、错误和取消。按接入优先级用可用模型确认 DeepSeek 等提供方的推理往返及流式行为，再选会话适配和旧 SQLite 历史迁移路径。 [旧版推理回填测试](https://github.com/c1589190/AgentMosire/blob/3645bf291e23feda2c43d04620e0a13a504b491e/AgentLib/src/test/java/io/github/c1589190/agentmosire/agentlib/llm/ReasoningEchoBackTest.java)

## Open Questions

- **兼容承诺。** 首期建议先明确行为兼容。旧 Maven 坐标、公开 Java API、PF4J JAR、配置和 SQLite 历史各自是否需要原地兼容，会直接影响适配和迁移工期。
- **MCP 消费方。** 首期面向固定服务身份还是多个用户，客户端现用 STDIO、旧式 Streamable HTTP 还是新版 MCP，将决定目录隔离、凭据和互通的验收范围。
- **持久化。** 批量裁决是否必须跨重启续跑。若必须，需先选决策包和执行进度的权威存储，并明确哪些宿主支持按包事务提交，哪些只能逐条对账。
- **父子运行形态。** 建议首个纵向验证先跑程序内的独立 Agent，再验证直接父级和跨进程回路。若首批宿主依赖跨进程隔离，需把审批与回执互通提前纳入首个变更。
