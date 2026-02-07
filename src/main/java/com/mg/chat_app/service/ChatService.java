package com.mg.chat_app.service;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.mg.chat_app.dto.ChatMessageDto;
import com.mg.chat_app.entity.Message;
import com.mg.chat_app.kafka.ChatMessageProducer;
import com.mg.chat_app.model.MessageStatus;
import com.mg.chat_app.repository.MessageRepository;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class ChatService {

    private final MessageRepository messageRepository;
    private final ChatMessageProducer producer;
    private final InputSanitizer inputSanitizer;

    @Transactional
    public ChatMessageDto sendMessage(Message message) {
        message.setContent(inputSanitizer.sanitize(message.getContent()));
        message.setStatus(MessageStatus.SENT);
        Message saved = messageRepository.save(message);

        ChatMessageDto dto = new ChatMessageDto(
                saved.getMessageId(),
                saved.getSenderId(),
                saved.getReceiverId(),
                saved.getContent());

        producer.publish(dto);
        return dto;
    }
}
