package io.github.c1589190.agentmosire.harness;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;

import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;

import static java.nio.charset.StandardCharsets.UTF_8;

public final class Main {
    private static final String USAGE = "用法：java -jar agent-harness.jar [--demo | --model <模型名> | --help]";

    private Main() {
    }

    public static void main(String[] args) {
        try {
            launch(args, new BufferedReader(new InputStreamReader(System.in, UTF_8)),
                    new PrintWriter(new OutputStreamWriter(System.out, UTF_8), true));
        } catch (IllegalArgumentException invalid) {
            System.err.println(invalid.getMessage());
            System.err.println(USAGE);
            System.exit(2);
        } catch (IOException failure) {
            System.err.println("终端输入读取失败。");
            System.exit(1);
        }
    }

    private static void launch(String[] args, BufferedReader input, PrintWriter output) throws IOException {
        if (args.length == 1 && args[0].equals("--help")) {
            output.println(USAGE);
            output.println("默认进入离线演示。模型模式读取 OPENAI_API_KEY 和可选的 OPENAI_BASE_URL。");
            return;
        }
        if (args.length == 0 || args.length == 1 && args[0].equals("--demo")) {
            output.println("AgentMosire 离线演示。输入「记 <内容>」或「读」，用 /exit 退出。");
            TerminalHarness.run(new DemoModel(), input, output);
        } else if (args.length == 2 && args[0].equals("--model") && !args[1].isBlank()) {
            ChatModel model = openAiModel(args[1], System.getenv("OPENAI_API_KEY"), System.getenv("OPENAI_BASE_URL"));
            output.println("AgentMosire 模型模式。用 /exit 退出。");
            TerminalHarness.run(model, input, output);
        } else {
            throw new IllegalArgumentException("无法识别启动参数。");
        }
    }

    static ChatModel openAiModel(String model, String apiKey, String baseUrl) {
        boolean customEndpoint = baseUrl != null && !baseUrl.isBlank();
        if (!customEndpoint && (apiKey == null || apiKey.isBlank())) {
            throw new IllegalArgumentException("请设置 OPENAI_API_KEY，或为本地兼容服务设置 OPENAI_BASE_URL。");
        }
        var options = OpenAiChatOptions.builder().model(model).apiKey(apiKey == null ? "" : apiKey);
        if (customEndpoint) {
            options.baseUrl(baseUrl);
        }
        return OpenAiChatModel.builder().options(options.build()).build();
    }
}
