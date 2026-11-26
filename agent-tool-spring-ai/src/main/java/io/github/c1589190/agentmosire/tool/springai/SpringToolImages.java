package io.github.c1589190.agentmosire.tool.springai;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.content.Media;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.util.MimeTypeUtils;

import io.github.c1589190.agentmosire.tool.ToolId;
import io.github.c1589190.agentmosire.tool.ToolImage;
import io.github.c1589190.agentmosire.tool.ToolResult;

/** Adapts authorized tool attachments to visual input on the next model call. */
public final class SpringToolImages {
    static final String CONTEXT_KEY = SpringToolImages.class.getName();

    private SpringToolImages() { }

    /** Apply inside the Spring AI tool loop, after any session-memory advisors. */
    public static Prompt attachTo(Prompt prompt) {
        if (!(prompt.getOptions() instanceof ToolCallingChatOptions options)
                || options.getToolContext() == null
                || !(options.getToolContext().get(CONTEXT_KEY) instanceof Attachments attachments)) {
            return prompt;
        }
        var images = attachments.drain();
        if (images.isEmpty()) {
            return prompt;
        }
        var history = new ArrayList<Message>(prompt.getInstructions());
        history.addAll(images);
        return prompt.mutate().messages(history).build();
    }

    /** Construct one per turn and pass it through Spring AI's request tool context. */
    public static final class Attachments {
        private static final int MAX_TURN_BYTES = 2 * ToolImage.MAX_BYTES;
        private final ArrayList<UserMessage> pending = new ArrayList<>();
        private long totalBytes;

        public Map<String, Object> context() {
            return Map.of(CONTEXT_KEY, this);
        }

        synchronized ToolResult add(ToolId id, ToolResult result) {
            long size = result.images().stream().mapToLong(ToolImage::size).sum();
            if (totalBytes + size > MAX_TURN_BYTES) {
                return ToolResult.of(ToolResult.Code.FAILED, "The turn's image budget has been reached");
            }
            totalBytes += size;
            List<Media> media = result.images().stream().map(image -> new Media(
                    MimeTypeUtils.parseMimeType(image.mimeType()), new ByteArrayResource(image.bytes()))).toList();
            pending.add(UserMessage.builder().text("Image attachment from tool " + id.externalName())
                    .media(media).build());
            return result;
        }

        private synchronized List<UserMessage> drain() {
            var images = List.copyOf(pending);
            pending.clear();
            return images;
        }
    }
}
