package com.mg.chat_app.service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import com.mg.chat_app.entity.Presence;
import com.mg.chat_app.repository.PresenceRepository;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class WebSocketSessionService {

    private static final Logger log = LoggerFactory.getLogger(WebSocketSessionService.class);
    private static final String SESSION_KEY = "ws:sessions";
    private static final String PRESENCE_PREFIX = "presence:";
    private static final Duration PRESENCE_TTL = Duration.ofMinutes(5);

    private final RedisTemplate<String, Object> redisTemplate;
    private final PresenceRepository presenceRepository;

    public void registerUser(Long userId, String sessionId) {
        redisTemplate.opsForHash().put(SESSION_KEY, userId.toString(), sessionId);
        redisTemplate.opsForValue().set(PRESENCE_PREFIX + userId, "ONLINE", PRESENCE_TTL);

        Presence presence = presenceRepository.findById(userId)
                .orElse(Presence.builder().userId(userId).build());
        presence.setIsOnline(true);
        presenceRepository.save(presence);

        log.info("User {} registered with session {}", userId, sessionId);
    }

    public void removeUser(Long userId) {
        redisTemplate.opsForHash().delete(SESSION_KEY, userId.toString());
        redisTemplate.delete(PRESENCE_PREFIX + userId);

        Optional<Presence> opt = presenceRepository.findById(userId);
        opt.ifPresent(p -> {
            p.setIsOnline(false);
            p.setLastSeen(LocalDateTime.now());
            presenceRepository.save(p);
        });

        log.info("User {} removed from sessions", userId);
    }

    public boolean isUserOnline(Long userId) {
        return Boolean.TRUE.equals(redisTemplate.opsForHash().hasKey(SESSION_KEY, userId.toString()));
    }

    public void renewPresence(Long userId) {
        redisTemplate.expire(PRESENCE_PREFIX + userId, PRESENCE_TTL);
    }
}
