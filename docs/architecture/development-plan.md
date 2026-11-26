# 新 AgentMosire 开发计划

第一版从两个消费者验证交付。普通 Maven 应用引入两个工具包，在程序内创建 Agent 并完成一次部分批准的决策包。另一个 Spring Boot 应用独立引入工具库和 MCP 适配，向外发布其中一个工具。两边使用相同的工具实现、工具标识和授权策略。 [架构设计](design.md) · [前期调查](../research/spring-ai-rebuild.md)

## Milestones

| 顺序 | 交付物 | 验收门槛 |
| --- | --- | --- |
| M0 · 验证框架边界 | Maven 骨架和最小端到端验证 | 标准 Advisor 能跨两次模型回复收集 `Ask`。SDK 端点能在正确身份下发布和调用受控工具。普通 Java 宿主启动时无需 Boot。 |
| M1 · 交付 AgentTool | 工具 Java 库、Spring AI 回调适配、两个示例工具包 | 消费者只实现一次业务方法。授权通过、拒绝、待裁决及错误具有稳定的结果码。 |
| M2 · 交付 Agent Runtime | Agent 实例、Session API、决策包、受控提交 | 两个 Agent 可并行运行。部分裁决、撤权、过期、取消和并发重提得到正确结果。 |
| M3 · 独立 MCP 发布 | WebMVC 发布适配、安全配置、SDK 客户端验收 | 通过真实 MCP 调用检查显式导出、请求身份、资源授权和错误映射。 |
| M4 · 集成与首版交付 | 消费示例、构建验证、模块使用说明 | 从 Maven 制品独立运行普通 Java 与 Boot 两种样例，核心验收使用固定模型和测试凭据。 |
| M5 · 父子协作 | 直接父 Agent 请求、等待与异步结果投递 | 部分批准由父级以自己的权限执行。并发请求、重试和模型失败均能按请求 ID 对账。 |
| M6 · 按宿主需求扩展 | 持久化、远端工具导入及其他 MCP 或进程形态 | 每个选项由实际消费端触发，先验证上游扩展能力和独立场景。 |

## Verification

首版 M0 至 M4 已通过 Maven `verify`。15 项测试覆盖参数校验、撤权、跨模型回复组包、显式封包、会话隔离、取消、过期、重复提交和 MCP HTTP 鉴权。另用已安装的 Maven 制品在源码目录外构建两个消费者。普通 Java 示例得到一条成功和一条拒绝的回执，Boot 示例由 MCP Java SDK 完成认证、列举和调用。两个依赖树分别确认 Java 宿主不依赖 Boot，MCP 宿主不依赖 Agent Runtime。模型和认证使用固定响应与本机测试凭据。

## 1. 验证框架边界

- [x] **1.1 锁定构建基线。** 建立 JDK 21 多模块 Maven 构建，使用 Spring AI BOM。Boot 应用单独管理 Boot 4.2 依赖。用有效 POM 和依赖树检查 Spring AI、MCP SDK、Jackson、Reactor 的版本，并验证基础工具库与 Agent 库在无 Boot 的外部 Maven 工程可启动。 [Spring AI 入门](https://docs.spring.io/spring-ai/reference/getting-started.html)
- [x] **1.2 验证 Agent 循环。** 用固定 `ChatModel` 返回两次工具调用及最终文本。让标准 `ToolCallingAdvisor` 调用受控回调，观察 `Ask` 登记、普通工具返回、回合范围和 Session 历史顺序。分别跑同步、流式、工具次数耗尽和取消场景。记录是否需要公开 Advisor 扩展点。 [ToolCallingAdvisor](https://docs.spring.io/spring-ai/reference/api/tools/tool-calling-advisor.html) · [Spring AI Session 集成](https://docs.spring.io/spring-ai/reference/api/session/chat-client.html)
- [x] **1.3 验证 MCP 身份与结果。** Spring AI WebMVC 无状态传输只注册一个程序化工具规格，使用 SDK 客户端通过 Streamable HTTP 验证 `tools/list`、`tools/call` 与请求认证。传入可信主体给受控调用器，核对正常、拒绝和结构化结果的映射。内部工具的直接查找和调用应被拒绝。 [Spring AI MCP Server 传输](https://docs.spring.io/spring-ai/reference/api/mcp/mcp-server-boot-starter-docs.html)

**通过条件。** 普通 Java 与 Boot 两种宿主都有可复现的验证入口。标准 Advisor 优先通过，扩展点缺口以最小复现记录。如果标准 MCP 注册可以承担固定目录与逐次授权，发布适配继续使用该路径。

## 2. 实现可独立复用的 AgentTool

- [x] **2.1 定义工具声明与装配。** 实现模块 ID、稳定工具 ID、工具定义与不可变注册表。提供两个使用纯 Java 工具声明的示例包。用重复 ID、别名冲突及非法 Schema 验证失败诊断。
- [x] **2.2 实现受控调用。** 将宿主可信身份、工具授权、资源约束及 `Allow`、`Block`、`Ask` 接入同一个 `ToolInvoker`。覆盖允许、无权限、跨资源、调用参数自报身份、缺少审批处理方式、业务失败和结果未知。统计工具实际执行次数核对拒绝路径。
- [x] **2.3 实现 Spring AI 适配。** 用 `ToolCallback` 发布指定条目，复用 Spring AI 工具定义与参数转换。通过模型工具名与稳定 ID 的映射定位当前工具，转换 `ToolResult` 的文本和机器可读结果码。验证宿主已有 `@Tool` 回调可显式绑定，模型猜测的未注册工具名无法执行。 [Spring AI Tool Calling](https://docs.spring.io/spring-ai/reference/api/tools.html)

**通过条件。** 一个普通 Maven 消费方仅引入 `agent-tool` 与两个工具包即可直接调用。相同工具从 Spring AI 回调调用时，授权和业务结果一致。

## 3. 实现 Agent Runtime 和决策包

- [x] **3.1 装配实例与会话。** 实现工厂、两个实例、每个实例可打开的会话句柄及生命周期。用官方 Session API 内存实现管理历史。验证实例工具隔离、会话隔离、同会话并发、取消及关闭后共享依赖仍可用。
- [x] **3.2 集成工具循环。** 将候选模块按实例筛选并绑定回合上下文，回调把 `Ask` 交给回合草案。正常结束封存非空草案，显式提交开启后续草案，取消和异常终止尚未封存的草案。验证同步与流式共享终态语义，回合结束事件在封包之后提供包 ID。
- [x] **3.3 实现版本化裁决。** 实现草案预览、包的内容版本、包级和逐条裁决、期限和宿主审批接口。验证未表态状态、旧版本裁决失效、已审批后改参数被拒绝、全拒、超时，以及一次回合显式封存两份互不重叠的包。
- [x] **3.4 实现受控提交和回执。** 原子认领包执行，获批条目经统一权限校验后执行。逐项记录结果，并用同包重复请求读取执行进度或最终回执。验证撤权、硬拒、参数快照、部分拒绝、过期及并发重提。ToolResult 可记录工具报告的未知结果。
- [x] **3.5 提供宿主事件。** 实例发送回合开始与结束、待裁决包创建和回执事件。同步结果以及流式终止事件可查询同一包 ID。复用 Spring AI 模型与工具观测能力，并验证审计记录中的事件关联及参数的访问控制。 [Spring AI 可观测性](https://docs.spring.io/spring-ai/reference/observability/index.html)

**通过条件。** 固定模型跨两次回复触发多个 `Ask`，一个 `Allow` 能先执行，一个 `Block` 始终被拒。只执行通过裁决和最终权限校验的条目，并发重提拿到同一包回执。

## 4. 实现独立 MCP 发布

- [x] **4.1 装配发布模块。** 使用 Spring AI MCP WebMVC 无状态传输及 Java SDK 注册接口，按显式导出清单注册工具。通过程序化工具处理器将 SDK 请求转入 `ToolInvoker`，验证结果码、结构化内容、待审批和错误返回。
- [x] **4.2 接入 HTTP 鉴权。** 对接 Spring Security Resource Server，在端点级校验访问范围，传递已认证主体。复用 Spring Security 的受保护资源元数据端点，检查可信 Origin 和部署端 TLS。测试无令牌、错误受众、缺少端点范围、授权主体无资源访问权限及标准认证发现流程。 [Spring Security JWT](https://docs.spring.io/spring-security/reference/servlet/oauth2/resource-server/jwt.html) · [受保护资源元数据](https://docs.spring.io/spring-security/reference/servlet/oauth2/resource-server/protected-resource-metadata.html) · [MCP 授权规范](https://modelcontextprotocol.io/specification/2025-11-25/basic/authorization)
- [x] **4.3 端到端互通。** 用官方客户端请求目录并调用工具。验证不同身份执行同一工具时数据范围不同，未导出工具既不可发现也不可调用。验证未配置审批处理方式时，`Ask` 返回待审批结果且工具执行次数为零。

**通过条件。** 一个独立 Boot 消费方仅依赖工具和 MCP 模块即可启动受保护端点。用 SDK 客户端完成列表与调用，结果与普通 Java 宿主对齐。

## 5. 首版交付

- [x] **5.1 做两个独立消费示例。** 普通 Maven 项目组合两个工具包后运行 Agent 并提交决策包。Boot 项目只发布一个工具。用真实 Maven 制品依赖运行示例，避免将源码树内可见性当成外部复用。
- [x] **5.2 验证构建和契约。** `./mvnw -B -ntp verify` 覆盖核心契约、模块边界和 MCP 互通。固定模型走默认验证，真实模型只需用目标提供方执行一次可选的部署冒烟，校验工具调用 ID、流式响应和历史往返。
- [x] **5.3 写使用说明。** 给出工具包最小样例、普通 Java 宿主与 MCP 发布示例、权限和决策接口，以及第一版内存状态的生命周期。将首次发布验收结果与解释依赖边界的架构文档放在一起。

**发布判据。** 架构中四个验收场景全部通过，并保留两个外部 Maven 消费方的运行结果。依赖树表明工具库独立于 Boot 和 Agent Runtime，MCP 发布库独立于 Agent Runtime。

## 6. 父子协作与后续增量

- [ ] **6.1 实现直接父级请求。** 在 `agent-runtime` 支持提问、提交非空草案和组合请求，提供等待与异步两种语义。父级按自身权限代执行，拒绝跨会话或越级提交。验证等待唤醒、重复请求和部分执行回执。 [旧版父子协作契约](https://github.com/c1589190/AgentMosire/blob/3645bf291e23feda2c43d04620e0a13a504b491e/openspec/changes/establish-agent-runtime/specs/parent-agent-coordination/spec.md)
- [ ] **6.2 验证异步回复。** 子 Agent 继续运行后，父级回复按请求与会话关联投递。模型请求失败后重试，成功后只消费一次。沿用 Spring AI 会话和 Advisor 扩展处，避免另建对话存储。 [旧版上下文投递契约](https://github.com/c1589190/AgentMosire/blob/3645bf291e23feda2c43d04620e0a13a504b491e/openspec/changes/establish-agent-runtime/specs/agent-context/spec.md)
- [ ] **6.3 按需加入重启恢复。** Spring AI JDBC Session Repository 保存模型历史，Spring JDBC 保存决策包和执行状态。验收覆盖提交认领后崩溃、外部结果未知、恢复后结果对账以及权限变化。 [Spring AI JDBC Session Repository](https://docs.spring.io/spring-ai/reference/api/session/jdbc.html)
- [ ] **6.4 按需扩展 MCP 和进程边界。** 接入外部 MCP 工具，评估 `MODERN`、STDIO 或多身份目录。需要跨进程 Agent 时，在受认证链路上绑定子实例身份，验证指定条目的一次性兑现和重复请求。 [Spring AI MCP Client Starter](https://docs.spring.io/spring-ai/reference/api/mcp/mcp-client-boot-starter-docs.html)

后续各项根据宿主需求安排，可以分别验收。第一版以 M0 至 M4 为发布门槛。
