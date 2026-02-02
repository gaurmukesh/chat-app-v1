package com.mg.chat_app.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;

import com.mg.chat_app.dto.ChatMessageDto;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class RedisMessageBridge {

    private static final Logger log = LoggerFactory.getLogger(RedisMessageBridge.class);
    private final RedisMessageListenerContainer listenerContainer;
    private final SimpMessagingTemplate messagingTemplate;
    private final RedisTemplate<String, Object> redisTemplate;
    private final GenericJackson2JsonRedisSerializer serializer = new GenericJackson2JsonRedisSerializer();

    public void publishToUser(Long userId, ChatMessageDto dto) {
        String channel = "chat:deliver:" + userId;
        redisTemplate.convertAndSend(channel, dto);
        log.debug("Published message to Redis channel {}", channel);
    }

    public void publishToGroup(Long groupId, ChatMessageDto dto) {
        String channel = "chat:group:" + groupId;
        redisTemplate.convertAndSend(channel, dto);
        log.debug("Published group message to Redis channel {}", channel);
    }

    public void subscribeUser(Long userId) {
        String channel = "chat:deliver:" + userId;
        listenerContainer.addMessageListener(createUserListener(userId), new ChannelTopic(channel));
        log.info("Subscribed to Redis channel {}", channel);
    }

    public void unsubscribeUser(Long userId) {
        String channel = "chat:deliver:" + userId;
        listenerContainer.removeMessageListener(null, new ChannelTopic(channel));
        log.info("Unsubscribed from Redis channel {}", channel);
    }

    public void subscribeGroup(Long groupId) {
        String channel = "chat:group:" + groupId;
        listenerContainer.addMessageListener(createGroupListener(groupId), new ChannelTopic(channel));
        log.info("Subscribed to Redis group channel {}", channel);
    }

    private MessageListener createUserListener(Long userId) {
        return (Message message, byte[] pattern) -> {
            try {
                ChatMessageDto dto = (ChatMessageDto) serializer.deserialize(message.getBody());
                messagingTemplate.convertAndSend("/topic/messages/" + userId, dto);
                log.debug("Delivered message to local WebSocket for userId={}", userId);
            } catch (Exception e) {
                log.error("Failed to deliver Redis message to user {}", userId, e);
            }
        };
    }

    private MessageListener createGroupListener(Long groupId) {
        return (Message message, byte[] pattern) -> {
            try {
                ChatMessageDto dto = (ChatMessageDto) serializer.deserialize(message.getBody());
                messagingTemplate.convertAndSend("/topic/groups/" + groupId, dto);
                log.debug("Delivered group message to local WebSocket for groupId={}", groupId);
            } catch (Exception e) {
                log.error("Failed to deliver Redis group message for group {}", groupId, e);
            }
        };
    }
}
