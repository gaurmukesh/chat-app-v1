package com.mg.chat_app.kafka;

import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

import com.mg.chat_app.dto.ChatMessageDto;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class ChatMessageProducer {

    private final KafkaTemplate<String, ChatMessageDto> kafkaTemplate;

    private static final String TOPIC = "chat-messages";
    private static final String GROUP_TOPIC = "chat-group-messages";

    public void publish(ChatMessageDto dto) {
        kafkaTemplate.send(TOPIC, dto.getReceiverId().toString(), dto);
    }

    public void publishGroupMessage(ChatMessageDto dto) {
        kafkaTemplate.send(GROUP_TOPIC, dto.getGroupId().toString(), dto);
    }
}
