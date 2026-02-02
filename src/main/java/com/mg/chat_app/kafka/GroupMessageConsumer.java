package com.mg.chat_app.kafka;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Service;

import com.mg.chat_app.dto.ChatMessageDto;
import com.mg.chat_app.service.GroupService;
import com.mg.chat_app.service.RedisMessageBridge;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class GroupMessageConsumer {

    private static final Logger log = LoggerFactory.getLogger(GroupMessageConsumer.class);
    private final GroupService groupService;
    private final RedisMessageBridge redisMessageBridge;

    @KafkaListener(topics = "chat-group-messages", groupId = "chat-group")
    public void consume(ChatMessageDto dto) {
        Long groupId = dto.getGroupId();
        log.info("Consuming group message for groupId={}, messageId={}", groupId, dto.getMessageId());

        List<Long> memberIds = groupService.getGroupMemberIds(groupId);
        for (Long memberId : memberIds) {
            if (!memberId.equals(dto.getSenderId())) {
                redisMessageBridge.publishToUser(memberId, dto);
            }
        }

        // Also broadcast to the group topic via Redis
        redisMessageBridge.publishToGroup(groupId, dto);
    }
}
