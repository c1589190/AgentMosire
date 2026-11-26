package io.github.c1589190.agentmosire.harness;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.AssistantMessage.ToolCall;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;

import io.github.c1589190.agentmosire.tool.JsonValues;
import io.github.c1589190.agentmosire.tool.ToolId;

/** A deterministic model for exercising the real runtime without a network connection. */
final class DemoModel implements ChatModel {
    @Override
    public ToolCallingChatOptions getOptions() {
        return ToolCallingChatOptions.builder().build();
    }

    @Override
    public ChatResponse call(Prompt prompt) {
        if (prompt.getInstructions().getLast() instanceof ToolResponseMessage toolReply) {
            var result = JsonValues.parseObject(toolReply.getResponses().getLast().responseData());
            if ("DECISION_QUEUED".equals(result.get("code"))) {
                return answer("等你确认后再保存。");
            }
            if (result.get("data") instanceof Map<?, ?> data && data.get("text") instanceof String note) {
                return answer(note.isEmpty() ? "便笺为空。" : "便笺：" + note);
            }
            return answer(String.valueOf(result.get("message")));
        }

        String message = prompt.getUserMessage().getText().strip();
        if (message.equals("读")) {
            return request(Notes.READ, Map.of());
        }
        if (message.startsWith("记 ") && !message.substring(2).isBlank()) {
            return request(Notes.WRITE, Map.of("text", message.substring(2).strip()));
        }
        return answer("离线演示支持「记 <内容>」和「读」。");
    }

    private static ChatResponse request(ToolId id, Map<String, Object> arguments) {
        var call = new ToolCall(UUID.randomUUID().toString(), "function", id.externalName(), JsonValues.write(arguments));
        return new ChatResponse(List.of(new Generation(AssistantMessage.builder().toolCalls(List.of(call)).build())));
    }

    private static ChatResponse answer(String text) {
        return new ChatResponse(List.of(new Generation(new AssistantMessage(text))));
    }
}
