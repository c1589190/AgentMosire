package io.github.c1589190.agentmosire.runtime;

import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.AdvisorChain;
import org.springframework.ai.chat.client.advisor.api.BaseAdvisor;
import org.springframework.core.Ordered;

import io.github.c1589190.agentmosire.tool.springai.SpringToolImages;

/** Sends attachments after session history has restored the matching tool response. */
final class ToolImageAdvisor implements BaseAdvisor {
    @Override
    public int getOrder() {
        return Ordered.LOWEST_PRECEDENCE - 1;
    }

    @Override
    public ChatClientRequest before(ChatClientRequest request, AdvisorChain chain) {
        var prompt = SpringToolImages.attachTo(request.prompt());
        return prompt == request.prompt() ? request : request.mutate().prompt(prompt).build();
    }

    @Override
    public ChatClientResponse after(ChatClientResponse response, AdvisorChain chain) {
        return response;
    }
}
