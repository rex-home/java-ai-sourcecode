package com.example.springaichatmemory.config;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 把消息窗口接到 ChatClient，业务接口不再手写读取、追加和裁剪历史的逻辑。 */
@Configuration
public class ChatMemoryConfiguration {
    @Bean
    public ChatMemory chatMemory() {
        // 不配置持久化仓库时，使用进程内存。10 是消息条数，不是 10 轮问答。
        // 不同 conversationId 的消息分别保存；这里没有完整历史归档功能。
        return MessageWindowChatMemory.builder().maxMessages(10).build();
    }

    @Bean
    public ChatClient memoryChatClient(ChatModel chatModel, ChatMemory chatMemory) {
        // Advisor 在调用模型前补入当前会话历史，并在调用链中保存本轮消息。
        // 业务代码只需传入 ChatMemory.CONVERSATION_ID，不需要再次手动 add。
        return ChatClient.builder(chatModel)
                .defaultAdvisors(MessageChatMemoryAdvisor.builder(chatMemory).build())
                .build();
    }
}
