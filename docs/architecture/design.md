# 新 AgentMosire 架构设计

新 AgentMosire 提供两个独立入口。**AgentTool** 让 Java 项目注册一次业务工具，统一控制进程内调用和 MCP 发布。**Agent Runtime** 让宿主组合多个工具包，实例化 Agent 并管理会话、请求、取消和关闭。AgentMosire 的自有逻辑集中在工具授权、实例装配和决策包。模型、工具调用循环、会话基础设施和 MCP 协议交给 Spring AI 及其 Java SDK。 [前期调查](../research/spring-ai-rebuild.md) · [Spring AI 2.1 发布公告](https://spring.io/blog/2026/11/20/spring-ai-2-1-0-GA-available-now/)

## Context

本项目从新代码和新数据模型开始。Maven 坐标、公开 API、工具名、插件协议、配置和存储均可按新架构定义。旧版调查作为决策包行为与边界案例的参考。首个公开里程碑交付可嵌入 Agent、独立 MCP 发布，以及进程内完整决策包。直接父级协作和重启恢复各有后续里程碑。 [开发计划](development-plan.md)

技术基线为 JDK 21、Maven 和 Spring AI 2.1.0 BOM。仅 MCP HTTP 接入和示例宿主使用 Spring Boot 4.2.0。普通 Java 宿主通过程序化装配调用工具和创建 Agent。基础库可以使用 Spring AI 的程序化接口，业务工具包只需依赖 `agent-tool`。 [Spring AI 入门](https://docs.spring.io/spring-ai/reference/getting-started.html) · [Spring Boot 系统要求](https://docs.spring.io/spring-boot/4.2/system-requirements.html)

## Decisions

### D1. 制品和依赖方向

以下模块名作为首版 Maven 坐标的 `artifactId` 候选。包名前缀使用 `io.github.c1589190.agentmosire`。

| 模块 | 包与职责 | 依赖边界 |
| --- | --- | --- |
| `agent-tool` | `tool` 提供工具定义、模块贡献、注册表、调用请求与结果。`tool.auth` 提供可信上下文、授权策略和受控调用器。 | 可随业务工具包单独引入。公共契约使用普通 Java 值与 JSON Schema，调用器与声明分开。 |
| `agent-tool-spring-ai` | `tool.springai` 把受控工具适配成 `ToolCallback`，并将显式引入的 Spring AI `@Tool` 或 `ToolCallback` 包装为工具贡献。 | 依赖 `agent-tool` 与 Spring AI 工具 API。已有 Spring AI Agent 的宿主可直接选用此模块。 |
| `agent-runtime` | `runtime` 管理 `AgentFactory`、实例、会话和回合。`runtime.decision` 管理决策包、审批、执行计划与回执。 | 依赖工具库及 Spring AI 适配。使用官方 ChatClient、模型和 Session API，Maven 使用者自行选择模型提供方。 |
| `agent-tool-mcp` | `tool.mcp` 提供只发布指定工具的 MCP 服务配置与结果映射。后续将外部 MCP 工具导入为工具贡献。 | 依赖工具库、Spring AI MCP WebMVC 传输、Java SDK 和 Spring Security。可以独立于 `agent-runtime` 部署。 |
| `agent-workspace` | `workspace` 将宿主授权的目录挂载为虚拟路径供工具使用。 | 可选工具包。使用 JDK 的安全目录操作与 `agent-tool`。 |
| `agent-search` | `search` 提供网页搜索、抓取与 DeepSeek 客户端。 | 可选工具包。使用 JDK HTTP Client、Jackson 和 `agent-tool`。 |

```text
Java tool package --> agent-tool <-- agent-tool-spring-ai <-- agent-runtime
                         ^                    |                     |
                         |                    v                     v
                   agent-tool-mcp       Spring AI tools      ChatClient / Session
                         |
                         v
                  Spring AI MCP / SDK
```

`ToolModule` 贡献一个稳定模块 ID 和显式工具清单。`ToolId` 由模块 ID 与工具名组成，适配层为模型和 MCP 生成确定性名称。逻辑 ID 和导出名称出现冲突时，装配失败并指出两个来源。`AgentFactory` 接受多个模块或宿主已组装的目录，每个 Agent 指定可见工具及授权策略。首版用 Maven 和 Java 装配模块，Spring Boot 宿主可通过 Bean 列表做同样的装配。共享模块、模型客户端和外部连接由其创建者关闭。Agent 实例关闭时撤销自身入口并释放会话引用、订阅和运行资源。

### D2. 工具授权与执行

| 契约 | 责任 |
| --- | --- |
| `ToolDefinition` | 描述稳定 ID、Schema、业务执行器、所需资源及 MCP 导出资格。作者可以由 Java 或 Spring AI 回调规范生成描述。 |
| `ToolInvoker` | 校验参数，取得当前工具定义，依据可信身份、Agent 工具范围、工具与资源权限及工具闸位得出 `Allow`、`Block` 或 `Ask`，按结果执行或返回。 |
| `InvocationContext` | 由 Java 宿主或 MCP 身份适配器构造。记录主体、Agent 或服务范围、请求 ID 和可选回合引用。模型生成的输入只用于业务参数。 |
| `ToolResult` | 提供机器可读结果码，附带受控文本、可选结构化内容和图片附件。调用方能区分成功、拒绝、等待裁决、工具失败及结果未知。 |

目录可见性用于生成模型工具列表和 MCP 服务面。真正调用时从当前策略重新校验身份、工具和资源。无策略、上下文缺失或校验失败时拒绝。硬拒直接终止。`Ask` 将已解析、已规范化的调用交给装配时指定的审批处理方式，缺少审批处理方式时返回 `APPROVAL_REQUIRED`。批准只能解除同一条调用的 `Ask`，执行前仍须复核权限、资源、工具定义和硬拒。通过同一个受控调用器进入业务实现，批准材料从宿主状态中读取。

首版策略支持按角色组合授权，并以资源范围和具体调用做交集。工具声明如何从已校验参数解析目标资源，执行器使用同一解析结果。需要运行中解析资源的工具通过上下文检查实际目标。权限主体由宿主提供，Spring Security 负责 HTTP 身份认证。进程内业务模块属于宿主信任边界。工具定义与执行使用同一套元数据，适配层可以补充 Schema 和传输结果所需信息。

### D3. Agent 回合由 Spring AI 驱动

候选入口为 `AgentFactory.create(config, modules)`、`Agent.openSession(...)`、`session.chat(...)`、`session.stream(...)` 和 `cancel`、`close`。模型在工厂装配时由宿主提供 `ChatModel`，Agent 和会话句柄管理身份范围。流式入口可使用 JDK `Flow.Publisher` 并通过 Reactor 适配上游。每个会话同时只执行一个回合，同一会话出现并发请求时返回 `SESSION_BUSY`，不同会话独立运行。启动和关闭归宿主管理，调用结果提供终止原因和相关决策包 ID。

采用 `ChatClient` 和恰好一个 `ToolCallingAdvisor`。每个请求注入已授权的 `ToolCallback` 集合及显式回合上下文。Spring AI 的名称解析回退保持关闭。工具次数和模型观测复用 Spring AI，运行时负责外层超时、取消和终态。Session API 管理消息、历史和压缩，`SessionAdvisors` 的 turn advisor 在工具循环外，memory advisor 在循环内。使用上游内存会话作为初始实现。 [Spring AI Tool Calling](https://docs.spring.io/spring-ai/reference/api/tools.html) · [Spring AI Session 与 ChatClient](https://docs.spring.io/spring-ai/reference/api/session/chat-client.html)

工具回调遇到 `Ask` 时返回 `DECISION_QUEUED`，供模型继续收集可直接执行及待裁决的调用。每次正常回合结束只封存一个非空草案，显式提交则封存当前草案并为本轮后续调用开启新草案。工具循环结束和封包由 Agent 回合包装层协调。流式终态在封包完成后发出，普通文本分片可即时转发。Spring AI 的单次模型回复级确认适用于独立确认场景，决策包批次统一由运行时处理。 [ToolCallingAdvisor 扩展点](https://docs.spring.io/spring-ai/reference/api/tools/tool-calling-advisor.html) · [Spring AI 工具确认](https://docs.spring.io/spring-ai/reference/api/tools/tool-call-confirmation.html)

同步和流式时序都先使用标准 Advisor 验证。若只有某个回调点无法满足，使用公开 Advisor 扩展点补齐。只有最小验证明确表明这些接口无法承载回合语义时，才在运行时接管必要的回合控制，模型和工具执行仍复用 `ChatModel`、`ToolCallingManager` 与上游消息类型。 [Spring AI ChatModel 工具调用](https://docs.spring.io/spring-ai/reference/api/tools/chatmodel-tool-calling.html)

用户图片使用 Spring AI `UserMessage` 和 `Media`。工具图片在单次回合的工具上下文中收集，模型请求 Advisor 在会话历史还原标准工具响应后添加带来源标记的视觉输入。MCP 发布器将同一附件映射到 SDK 的 `ImageContent`。图片预算与工具结果分开校验。 [Spring AI 2.1 消息模型](https://spring.io/blog/2026/11/20/spring-ai-2-1-0-GA-available-now/)

### D4. 决策包是版本化批次

运行时持有本轮草案，`DecisionService` 持有已封存的包及其审批和执行进度。宿主通过 `DecisionReady` 事件与回合结果取得包 ID，再通过 `DecisionService` 读取和裁决。Agent 回合输出包 ID 后即可交还控制权，宿主可选择等待或异步处理。审批人与执行人的身份由宿主派生，`DecisionCommitted` 记录身份与条目结果码。模型判断可辅助分流，审批权限由宿主策略确定。

| 包状态 | 状态转换与可观察结果 |
| --- | --- |
| 草案 | 属于一个正在运行的回合。接受 `Ask` 调用，支持预览与显式封存。取消或失败时丢弃尚未封存的草案。 |
| `PENDING` | 非空草案形成不可变版本。提议者可在提交前选择保留的条目，产生新内容版本。旧版本裁决失效。支持包级裁决和逐条裁决，未表态条目仍待裁决。 |
| `DECIDED` | 确定最终获批集合。只有当前版本、审批者身份和期限校验均通过才进入该状态。空获批集合可直接形成全拒回执。 |
| `EXECUTING` | 以包 ID 原子认领一次提交。提交方读取已固定的条目和执行身份，从普通授权链重新验证后执行已批准子集。并发提交读取同一个执行进度。 |
| `CLOSED` | 存有同一个可重读回执，每个条目带成功、拒绝、失败或未知等结果。工具副作用由工具本身控制，通用执行器逐条记录。 |
| `EXPIRED` / `CANCELLED` | 在执行认领之前进入终态，不启动工具。认领后提出取消则停止尚未认领的条目，已经开始的条目按真实结果记账。 |

条目保存工具 ID、参数快照与摘要、调用者和稳定序号。内存执行的一次性凭据绑定创建它的调用器与不可变工具定义。每次裁决绑定包 ID、版本及条目引用，可保存逐项备注。同一个包在内存状态存续期间只认领一次，回执可按包 ID 对账。工具返回明确失败按失败记账，感知到副作用未确认的工具应报告 `UNKNOWN`，随后查询或人工对账。

宿主结算模式把审批、参数和回执交给可注入的 `DecisionStore`。`prepareCommit` 校验当前版本与权限，生成只读定序计划。`completeCommit` 在宿主事务中以批量条件更新记录回执和业务版本引用。宿主可原子写入时间线事件并从事件投影已提交快照。宿主重启后可以通过同一存储读取已保存的版本和回执。默认模式继续使用内存，Spring AI Session 独立管理模型历史。 [Spring AI JDBC Session Repository](https://docs.spring.io/spring-ai/reference/api/session/jdbc.html)

直接父级协作由 `agent-runtime` 的下一里程碑提供。子 Agent 可只提问、只交非空草案，或把两者组合。父侧使用自己的当前权限裁决并代执行，按请求 ID 向对应子会话返回结果。等待模式能被直系子级请求唤醒，异步模式在后续一次有效模型请求中投递，模型失败可重投。跨进程部署再加受认证的连接和一次性兑现，复用同一包语义与回执。

### D5. MCP 采用显式导出和上游服务端

首版用 Spring AI 的无状态 WebMVC Transport 提供 Streamable HTTP，并与 MCP Java SDK 客户端协商 2025-11-25 协议。现有工具发布功能每次调用独立鉴权，使用标准传输即可处理连接与协议。HTTP 资源服务器验证签发者、受众和访问范围，用 Spring Security 的受保护资源元数据端点对接宿主的授权服务器。HTTP 传输验证请求的 Host 和 Origin，部署端配置 TLS。宿主通过程序化注册明确指定要导出的工具。启动时验证导出名称和安全配置。 [Spring AI MCP Server 传输](https://docs.spring.io/spring-ai/reference/api/mcp/mcp-server-boot-starter-docs.html) · [Spring Security 受保护资源元数据](https://docs.spring.io/spring-security/reference/servlet/oauth2/resource-server/protected-resource-metadata.html) · [MCP HTTP 授权规范](https://modelcontextprotocol.io/specification/2025-11-25/basic/authorization)

首版每个 MCP 端点有一个固定导出目录和相应的访问范围。拥有该范围的主体可以列出该端点公开工具，调用时按真实主体分别校验资源。不同目录的授权组可部署独立端点。这样可以使用 SDK 标准目录与注册机制。需要同端点按主体隐藏工具时，先验证上游按请求过滤扩展点，再纳入下一阶段。业务拒绝、待审批和失败由程序化 MCP 工具规格映射为明确的 `CallToolResult`，保留协议错误与业务结果的区别。 [Spring AI Streamable HTTP Starter](https://docs.spring.io/spring-ai/reference/api/mcp/mcp-streamable-http-server-boot-starter-docs.html) · [Spring AI v2.1.0 转换源码](https://github.com/spring-projects/spring-ai/blob/v2.1.0/mcp/common/src/main/java/org/springframework/ai/mcp/McpToolUtils.java)

后续导入 MCP 时，用 Spring AI Client Starter 或 SDK 管理连接与发现，按宿主映射标记来源并应用本地授权。调用身份和目录缓存按连接的权限边界组织。MCP `MODERN` 及 STDIO 作为独立入口的次阶段选项，依据实际消费者选择。 [Spring AI MCP Client Starter](https://docs.spring.io/spring-ai/reference/api/mcp/mcp-client-boot-starter-docs.html)

## Acceptance Scenarios

### Requirement: 同一个工具实现可通过多种入口复用

Java 调用、Spring AI 回调和 MCP 入口 SHALL 共享工具定义和受控调用器。MCP 目录 SHALL 仅包含显式导出并允许在该端点发现的工具。

#### Scenario: 两个主体在不同入口访问工具

- **GIVEN** 宿主注册资源读取工具及一个禁止导出的内部工具，甲有端点访问范围和资源授权，乙只有端点访问范围
- **WHEN** 两人列出 MCP 工具并从 Java、Agent 和 MCP 请求同一资源
- **THEN** 工具目录展示读取工具，内部工具无法通过 MCP 发现或调用。甲在授权资源范围内成功，乙的读取请求均被拒绝。没有端点访问范围的主体无法列出或调用 MCP 工具。

### Requirement: 宿主可以组合包并管理实例

运行库 SHALL 组合多个模块并隔离实例身份、会话和工具集。

#### Scenario: 两个 Agent 共用部分模块

- **GIVEN** 普通 Java 宿主装配两个模块，Agent A 使用两个，Agent B 使用其中一个
- **WHEN** 两个 Agent 并行对话，随后关闭 Agent A
- **THEN** A 与 B 只看到各自授权的工具和历史，B 的会话与共享模块继续可用。A 关闭后的新调用返回明确终态。

### Requirement: 待裁决调用能跨模型回复组包并只兑现一次

运行时 SHALL 记录 `Ask` 和版本化裁决。提交 SHALL 仅兑现当前有效授权的获批条目，并产出可重复查询的同一回执。

#### Scenario: 部分批准及并发提交

- **GIVEN** 同一回合跨两次回复触发三个 `Ask`，另有一个 `Allow` 和一个 `Block`
- **WHEN** 回合结束后批准两条，撤销其中一条的权限，同时两次提交该包
- **THEN** 三条 `Ask` 在裁决前均未执行，普通调用成功且硬拒未进包。仍有权限的获批条目只执行一次，其他条目记录拒绝，共享一个最终回执。

#### Scenario: 取消或过期遇到待执行的草案

- **GIVEN** 一个回合正在收集 `Ask`，或一个已封存的包尚未开始执行
- **WHEN** 回合被取消、包到期，或提交方尝试使用已失效的内容版本
- **THEN** 未封存草案不再提交，过期包进入终态，旧版本裁决被拒绝。相关工具的副作用计数为零。

## Risks / Trade-offs

| 遇到的约束 | 设计决策和验证 |
| --- | --- |
| Starter 全局自动发现回调 | 模型工具显式按请求提供，MCP 使用导出清单。用未注册回调和禁外发工具验证都无法通过猜名执行。 |
| 模型循环和会话 Advisor 的相对顺序 | 先验证标准装配的两轮工具调用、结果写回、流式取消和工具次数。模型循环回退方案由这些验证结果触发。 |
| 流式取消和执行时间无法完全同步 | 回合终止只封存一次。已执行结果照实记录，未封存草案关闭，外部结果未知进入对账。 |
| 多用户目录与通用 SDK 注册方式 | 先以受保护的端点提供可预测的固定目录，逐次调用按主体授权。更细粒度的目录需求由实际消费方驱动。 |

## Open Questions

首轮验收采用两份小型示例工具包、固定响应模型和受保护的 MCP 端点。正式消费方的首选模型、额外 MCP 传输、持久化和父子进程部署需求可以影响后续里程碑。首轮依赖锁定以后续验证产出的依赖树为准。 [开发计划](development-plan.md)
