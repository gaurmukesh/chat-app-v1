package com.mg.chat_app.controller;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Controller;

import com.mg.chat_app.dto.ChatMessageDto;
import com.mg.chat_app.dto.ReadReceiptDto;
import com.mg.chat_app.entity.Message;
import com.mg.chat_app.model.MessageStatus;
import com.mg.chat_app.repository.MessageRepository;

import lombok.RequiredArgsConstructor;

@Controller
@RequiredArgsConstructor
public class ChatWebSocketController {

    private static final Logger log = LoggerFactory.getLogger(ChatWebSocketController.class);
    private final SimpMessagingTemplate messagingTemplate;
    private final MessageRepository messageRepository;

    @MessageMapping("/read")
    public void markAsRead(@Payload ChatMessageDto dto) {
        Message msg = messageRepository.findById(dto.getMessageId())
                .orElseThrow(() -> new RuntimeException("Message not found"));

        // Only the receiver can mark a message as read
        if (!msg.getReceiverId().equals(dto.getReceiverId())) {
            log.warn("Receiver mismatch for message {}: expected {} but got {}", dto.getMessageId(), msg.getReceiverId(), dto.getReceiverId());
            return;
        }

        msg.setStatus(MessageStatus.READ);
        messageRepository.save(msg);

        ReadReceiptDto receipt = new ReadReceiptDto(
                dto.getMessageId(), dto.getSenderId(), dto.getReceiverId(), "READ");

        messagingTemplate.convertAndSend("/topic/read/" + dto.getSenderId(), receipt);
    }

    @MessageMapping("/typing")
    public void typing(@Payload ChatMessageDto dto) {
        messagingTemplate.convertAndSend("/topic/typing/" + dto.getReceiverId(), dto.getSenderId());
    }
}
