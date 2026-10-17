package com.example.springaichatmemory.web;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 按会话 ID 连续问答。演示接口没有登录体系，仅用于本机学习。 */
@RestController
@RequestMapping("/api/memory-chat")
public class MemoryChatController {
    private final ChatClient chatClient;

    public MemoryChatController(ChatClient chatClient) {
        this.chatClient = chatClient;
    }

    @PostMapping
    public MemoryChatResponse chat(@Valid @RequestBody MemoryChatRequest request) {
        // 禁止空 ID，避免不同请求意外落到同一默认会话；会话 ID 本身不是身份认证。
        String answer = chatClient.prompt()
                .system("你是熟悉售后业务的 Java AI 助手，回答简洁。只根据当前会话回答个人信息；没有信息就明确说不知道，不要猜测。")
                .user(request.message())
                .advisors(advisor -> advisor.param(ChatMemory.CONVERSATION_ID, request.conversationId()))
                .call()
                .content();
        return new MemoryChatResponse(request.conversationId(), answer);
    }

    /** 同一 conversationId 续接上下文；message 是本轮输入，不用重复提交历史。 */
    public record MemoryChatRequest(
            @NotBlank @Size(max = 128) String conversationId,
            @NotBlank @Size(max = 4000) String message) {}

    /** 回传会话 ID，调用方可以核对回答属于哪条会话。 */
    public record MemoryChatResponse(String conversationId, String answer) {}
}
