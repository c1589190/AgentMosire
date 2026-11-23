# AgentMosire

AgentMosire 是 Java Agent 运行时。首期版本为 `0.0.1-early`。

| 模块 | 源码与测试 |
| --- | --- |
| AgentLib | `AgentLib/src/main/java` 与 `AgentLib/src/test/java` |
| Brain | `Brain/src/main/java` 与 `Brain/src/test/java` |
| Main | `Main/src/main/java` 与 `Main/src/test/java` |

## 构建

在项目根目录使用 JDK 21 和仓库自带的 Maven Wrapper。

- `./mvnw -B -ntp validate` 核对 Maven 模块和工具链。
- `./mvnw -B -ntp verify` 构建三模块并运行测试、格式检查和静态分析。
- `python3 scripts/check-module-boundaries.py` 核对模块依赖方向。

## 本地运行

构建后可用离线模型启动 Main。

```sh
java -jar Main/target/mosire.jar run --fake --no-mcp-expose --port 0 --data-dir .work/demo
```

启动输出中有 Admin 和 Debug 地址。用 Admin 的 `/health` 检查服务，用 Debug 的 `/api/chat` 提交对话。

## Java 嵌入

在根目录运行 `./mvnw -B -ntp install` 后，消费方可依赖 `io.github.c1589190:brain-mosire:0.0.1-early`。

```java
import io.github.c1589190.agentlib.agent.AgentConfig;
import io.github.c1589190.agentlib.llm.LlmResponse;
import io.github.c1589190.brain.runtime.AgentRuntime;
import io.github.c1589190.brain.runtime.AgentSpec;

var spec = new AgentSpec(AgentConfig.builder("demo").build());
try (var runtime = AgentRuntime.builder(spec, request -> LlmResponse.text("你好")).build()) {
  System.out.println(runtime.chat("打个招呼").finalText().orElseThrow());
}
```

会话窗口、压缩和注入由 Brain 管理。需要替换压缩策略时，实现 `io.github.c1589190.brain.compact.ContextCompactor` 并通过 `AgentRuntime.Builder.contextCompactor(...)` 装配。Main 使用同包的默认实现。

## 许可证与源码来源

AgentMosire 采用 Apache License 2.0。三个模块的源码与测试、Maven Wrapper、构建配置和示例模板来自 `ProjectMosire` 的 `df1b4202755127b4e86c29a73de7bd9944eed1e5`。迁入的源码调整了包名、模块边界和运行时装配。许可和归属见 [LICENSE](LICENSE) 与 [NOTICE](NOTICE)。

首期范围和架构见 [OpenSpec 提案](openspec/changes/establish-agent-runtime/proposal.md)。
