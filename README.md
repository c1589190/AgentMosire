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

## 宿主裁决

工具可以给出 `ALLOW`、`BLOCK` 或 `ASK`。回合内的 `ASK` 返回 `DECISION_QUEUED`，待裁决条目可通过 `AgentSession.previewDraft()` 预览，或用 `submitDraft()` 提前封包。回合正常结束时，终态包含本轮的决策包 ID。宿主按版本提交整包或逐条裁决，并通过 `DecisionService.commit()` 获取可重复读取的回执。执行使用已记录的原始工具调用并重新检查当前权限。

首版的决策包在当前进程内提供版本、过期、取消和重复提交保护。默认会话使用 Spring AI 的内存仓库。进程内工具包由宿主信任，审批 API 的调用者身份由宿主绑定。 [架构设计](docs/architecture/design.md) · [开发计划](docs/architecture/development-plan.md)
