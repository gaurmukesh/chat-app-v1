package com.mg.chat_app.kafka;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Service;

import com.mg.chat_app.dto.ChatMessageDto;
import com.mg.chat_app.entity.Message;
import com.mg.chat_app.model.MessageStatus;
import com.mg.chat_app.repository.MessageRepository;
import com.mg.chat_app.service.RedisMessageBridge;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class ChatMessageConsumer {

    private static final Logger log = LoggerFactory.getLogger(ChatMessageConsumer.class);
    private final MessageRepository messageRepository;
    private final RedisMessageBridge redisMessageBridge;

    @KafkaListener(topics = "chat-messages", groupId = "chat-group")
    public void consume(ChatMessageDto dto) {
        log.info("Consuming message for receiverId={}, messageId={}", dto.getReceiverId(), dto.getMessageId());

        // Publish to Redis for cross-node delivery
        redisMessageBridge.publishToUser(dto.getReceiverId(), dto);

        // Update DB status
        Message msg = messageRepository.findById(dto.getMessageId()).orElse(null);
        if (msg != null) {
            msg.setStatus(MessageStatus.DELIVERED);
            messageRepository.save(msg);
        }
    }
}
