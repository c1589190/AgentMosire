# AgentMosire

AgentMosire 提供可复用的 Java 工具库和可嵌入的 Agent 运行时。工具实现可以由宿主直接调用、接入 Spring AI 或发布为 MCP。各入口使用相同的工具描述与授权规则。Agent 回合把需要审批的工具调用组成决策包，由宿主裁决后执行。

## 构建

使用 JDK 21 和 Maven Wrapper。Spring AI 基线为 2.1.0，HTTP MCP 示例使用 Spring Boot 4.2.0。

```sh
./mvnw -B -ntp verify
```

默认验证使用固定响应模型和本机 MCP 客户端，无需模型服务或账号。MCP 互通测试使用临时回环端口。

## 独立命令行

`agent-harness` 是一个可执行 JAR，装配 Agent Runtime 和一个内存便笺工具包。只需 JDK 21 即可运行离线演示。

```sh
./mvnw -B -ntp -pl agent-harness -am package
java -jar agent-harness/target/agent-harness-0.1.0-alpha1.jar
```

输入 `记 今天要读书`，在审批提示输入 `y`，然后输入 `读` 查看保存结果。直接回车会拒绝写入。输入 `/exit` 结束，会话和便笺随进程退出。

使用实际模型时，从环境变量提供 `OPENAI_API_KEY`，并用 `--model` 指定支持工具调用的模型。连接 OpenAI 兼容端点时，设置 `OPENAI_BASE_URL`，例如 `http://localhost:11434/v1`。

```sh
java -jar agent-harness/target/agent-harness-0.1.0-alpha1.jar --model "$OPENAI_MODEL"
```

模型模式与离线演示使用同一套工具授权和人工审批。 [Spring AI 模型配置](https://docs.spring.io/spring-ai/reference/api/chat/openai-chat.html#_connection_details)

## Maven 模块

模块的 groupId 为 `io.github.c1589190.agentmosire`，当前版本为 `0.1.0-alpha1`。

| artifactId | 入口 |
| --- | --- |
| `agent-tool` | `ToolModule`、`ToolRegistry` 和 `ToolInvoker` 管理工具注册、参数校验、身份和资源授权。 |
| `agent-tool-spring-ai` | `SpringTools` 可从受控工具生成 `ToolCallback`，也可以显式纳入已有 Spring AI 回调。 |
| `agent-runtime` | `AgentFactory` 接收 Spring AI `ChatModel`，组合多个模块。`AgentSession` 提供同步和流式交互，`DecisionService` 提供审批和提交。 |
| `agent-tool-mcp` | `McpPublisher` 将显式选中的工具发布为无状态 Streamable HTTP，`McpHttpSecurity` 提供 Spring Security 配置。 |
| `agent-workspace` | `WorkspaceTools` 将宿主配置的目录挂为虚拟路径，提供列举、文本读写和图片读取。 |
| `agent-search` | `SearchTools` 装配宿主提供的网页搜索和抓取，`DeepSeekWebClient` 提供内置实现。 |

普通 Maven 宿主可直接使用前三个库。模型提供方由宿主选择。启用工具时，模型应提供 `ToolCallingChatOptions`，工具通过 Agent 目录配置。完整接入见 [工具声明](examples/sample-tools/src/main/java/io/github/c1589190/agentmosire/example/catalog/CatalogTools.java) 和 [Java 宿主](examples/java-host/src/main/java/io/github/c1589190/agentmosire/example/java/JavaHost.java)。

## 运行示例

先安装本地 Maven 制品，再单独运行普通 Java 消费方。示例装配两个工具包，固定模型跨两次回复请求多个调用，宿主只批准一个条目。

```sh
./mvnw -B -ntp install
./mvnw -B -ntp -f examples/java-host/pom.xml compile org.codehaus.mojo:exec-maven-plugin:3.5.0:exec \
  -Dexec.executable=java '-Dexec.args=-cp %classpath io.github.c1589190.agentmosire.example.java.JavaHost'
```

HTTP MCP 示例由宿主的 OAuth 授权服务器签发 JWT。配置实际的资源 URL、令牌受众、签发者和外部入口 Host，再显式启用发布。示例工具目录要求 `catalog.read` scope。`alice` 可查询 `private`，有 scope 的其他主体可查询 `public`。生产 TLS 可由宿主或反向代理终止。

```sh
java -jar examples/mcp-host/target/mcp-host-0.1.0-alpha1.jar \
  --mosire.mcp.enabled=true \
  --mosire.mcp.resource-uri=https://catalog.example.com/mcp \
  --mosire.mcp.allowed-host=catalog.example.com \
  --spring.security.oauth2.resourceserver.jwt.issuer-uri=https://login.example.com \
  --spring.security.oauth2.resourceserver.jwt.audiences=https://catalog.example.com/mcp
```

客户端连接 `/mcp` 并带上针对该资源签发的 Bearer token。发布端可用范围由显式清单与工具的导出资格决定，业务权限在每次调用时检查。 [HTTP 宿主代码](examples/mcp-host/src/main/java/io/github/c1589190/agentmosire/example/mcp/McpHost.java)

同一 WebMVC 宿主可以装配多个 `McpPublisher`，为每个实例注册唯一端点、路由和匹配的安全链。资源 URI 是该端点的公开地址。客户端从该端点的 `401` 响应发现对应的 OAuth 元数据。

## 宿主裁决

工具可以给出 `ALLOW`、`BLOCK` 或 `ASK`。回合内的 `ASK` 返回 `DECISION_QUEUED`，待裁决条目可通过 `AgentSession.previewDraft()` 预览，或用 `submitDraft()` 提前封包。回合正常结束时，终态包含本轮的决策包 ID。宿主按版本提交整包或逐条裁决，并通过 `DecisionService.commit()` 获取可重复读取的回执。执行使用已记录的原始工具调用并重新检查当前权限。

默认的 `commit(...)` 在进程内逐条执行。需要业务原子结算时，用 `AgentFactory.create(config, modules, decisionStore)` 接入宿主专用的 `DecisionStore`。用 `reviewItems` 提供逐项审批备注，再用 `prepareCommit(packageId, version, approver)` 取得定序的获准调用。可先用 `plan.calls()` 预演。若需更改审批，`reopenReview` 会作废旧计划。

正式结算时，在宿主事务内校验业务基点、应用获准调用、分配业务版本，并用 `completeCommit(plan, revisionRef)` 记录回执。多个包可以通过 `completeCommit(List<Completion>, revisionRef)` 一次完成。事务型存储应在同一个事务中写入业务变更和快照。时间线型存储可将回执写入同一个原子业务事件，由 `DecisionStore` 从事件投影已提交快照。重复准备时先读取 `plan.receipt()`。示例用的 `InMemoryDecisionStore` 适用于无需重启恢复的宿主；持久化由宿主接入。事务成功后，宿主可以发布返回的回执。

默认会话使用 Spring AI 的内存仓库。进程内工具包由宿主信任，审批 API 的调用者身份由宿主绑定。 [架构设计](docs/architecture/design.md) · [开发计划](docs/architecture/development-plan.md)

## 工作区、图片和联网工具

用 `new WorkspaceTools(List.of(new WorkspaceTools.Mount("reference", path, false)))` 注册工作区，再把 `module()` 装进 Agent 或 MCP。资源类型为 `workspace`，授权按虚拟路径和 `list`、`read`、`write` 操作核对。磁盘挂载需要文件系统提供 JDK `SecureDirectoryStream`，目录遍历只允许访问普通文件和目录，工具输出使用虚拟路径。支持 PNG、JPEG、GIF 和 WebP。

`session.chat(UserMessage)` 和 `stream(UserMessage)` 接受 Spring AI 的带图消息。工具可通过 `ToolResult.okImage` 返回附件。MCP SDK 客户端收到原生图片内容。Agent 运行时通过 Spring AI 给视觉模型发送工具附件，需要选用支持视觉输入的模型。

用 `SearchTools.module(searchProvider, fetchProvider)` 启用公网搜索与抓取。两项可独立启用。搜索需授予资源 `web/public/search`，抓取需授权目标 DNS 名。会跨域抓取的提供方还需要 `web/public/fetch` 授权。宿主负责提供凭据并关闭客户端。

真实验收使用临时合成图片、测试决策包及公开网页。默认模型为 DeepSeek Flash，从环境读取 `DEEPSEEK_API_KEY`。可用 `AGENTMOSIRE_LIVE_BASE_URL`、`AGENTMOSIRE_LIVE_MODEL` 和 `AGENTMOSIRE_LIVE_API_KEY` 指定兼容视觉及工具调用的自有模型站点。搜索验收仍使用 DeepSeek。

```sh
./mvnw -B -ntp -Dagentmosire.live=true verify
```
