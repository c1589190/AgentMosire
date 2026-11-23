# AgentMosire

AgentMosire 是 Java Agent 运行时。首期版本为 `0.0.1-early`。

| 模块 | 源码与测试 |
| --- | --- |
| AgentLib | `AgentLib/src/main/java` 与 `AgentLib/src/test/java` |
| Brain | `Brain/src/main/java` 与 `Brain/src/test/java` |
| Main | `Main/src/main/java` 与 `Main/src/test/java` |
| Workspace、Docs、Search、Bash | `plugins/<模块>/src/main/java` 与 `plugins/<模块>/src/test/java` |
| Distribution | `Distribution/src/assembly` |

## 构建

在项目根目录使用 JDK 21 和仓库自带的 Maven Wrapper。

- `./mvnw -B -ntp validate` 核对 Maven 模块和工具链。
- `./mvnw -B -ntp verify` 构建宿主、插件和整合包，并运行测试、格式检查和静态分析。
- `python3 scripts/check-module-boundaries.py` 核对模块依赖方向。

## 本地运行

构建后可用离线模型启动 Main。

```sh
java -jar Main/target/mosire.jar run --fake --no-mcp-expose --port 0 --data-dir .work/demo
```

启动输出中有 Admin 和 Debug 地址。用 Admin 的 `/health` 检查服务，用 Debug 的 `/api/chat` 提交对话。

## 插件与整合包

- **薄包**使用 `Main/target/mosire.jar`。把各插件 `target/` 下的主制品 JAR 放入 `.agentmosire/plugins/`。Docs 和 Bash 都需要 Workspace。
- **整合包**位于 `Distribution/target/agentmosire-0.0.1-early-bundle.zip`。从解压后的顶层目录运行 `java -jar mosire.jar run --fake --no-mcp-expose --port 0`。四个插件已经放在默认目录。
- `--data-dir` 重定位数据根与默认插件目录。`config.json` 中的 `plugins.dir` 可显式指定其他插件目录。宿主只扫描目录顶层的 JAR，为每个插件提供 `plugins/<id>/config.json` 和同目录下的独立数据空间。

在 `.agentmosire/plugins/workspace/config.json` 中命名可挂载的宿主目录。根必须存在，且与运行时数据及插件目录分离。

```json
{"roots":{"knowledge":"/srv/agent-shares/knowledge"}}
```

再把下面的 `plugins` 节放入 `.agentmosire/agentconfigs/main.json` 或现有子 Agent 模板。多个模板可以引用同一个根。子 Agent 的挂载还需被父 Agent 的当前挂载覆盖。用 `subdir` 缩小到指定子目录，用 `capabilities` 选择允许访问的插件。挂载模式默认为 `read-only`。

```json
{
  "plugins": {
    "workspace": {
      "mounts": {
        "shared": {
          "root": "knowledge",
          "mode": "read-only",
          "capabilities": ["docs", "bash"]
        }
      }
    }
  }
}
```

Docs 用 `shared/notes.md` 读文件，Bash 用 `shared` 作为虚拟工作目录。Bash 使用固定的进程环境，并需要 Linux 挂载隔离。隔离能力不足时调用返回失败。运行时继续按 Agent 的工具权限决定各插件工具能否调用。

要让子 Agent 使用 `doc_read`，模板的 `allowedTools` 需包含该工具，旧版工具禁用开关 `readOnly` 需为 `false`。共享目录的写入权限由 Workspace 的 `mode` 独立约束。

Search 的 `plugins/search/config.json` 可分别声明搜索和抓取。以下配置启用搜索，并从环境变量取得凭据。抓取使用同样结构的 `fetch` 项。Docs 可在 `plugins/docs/config.json` 用 `enabled` 控制工具注册，Bash 配置位于 `plugins/bash/config.json`。

```json
{"search":{"credentialsRef":"env:DEEPSEEK_API_KEY"}}
```

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

AgentMosire 采用 Apache License 2.0。运行时和四个插件的源码与测试、Maven Wrapper、构建配置和示例模板来自 `ProjectMosire` 的 `df1b4202755127b4e86c29a73de7bd9944eed1e5`。迁入的源码调整了包名、模块边界和运行时装配。许可和归属见 [LICENSE](LICENSE) 与 [NOTICE](NOTICE)。

首期范围和架构见 [OpenSpec 提案](openspec/changes/establish-agent-runtime/proposal.md)。
