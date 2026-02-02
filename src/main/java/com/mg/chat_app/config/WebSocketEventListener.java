package com.mg.chat_app.config;

import java.security.Principal;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.messaging.SessionConnectEvent;
import org.springframework.web.socket.messaging.SessionDisconnectEvent;

import com.mg.chat_app.service.WebSocketSessionService;

import lombok.RequiredArgsConstructor;

@Component
@RequiredArgsConstructor
public class WebSocketEventListener {

    private static final Logger log = LoggerFactory.getLogger(WebSocketEventListener.class);
    private final WebSocketSessionService sessionService;
    private final SimpMessagingTemplate messagingTemplate;

    @EventListener
    public void handleWebSocketConnect(SessionConnectEvent event) {
        StompHeaderAccessor accessor = StompHeaderAccessor.wrap(event.getMessage());
        Principal principal = accessor.getUser();
        if (principal != null) {
            Long userId = Long.valueOf(principal.getName());
            String sessionId = accessor.getSessionId();
            sessionService.registerUser(userId, sessionId);
            log.info("WebSocket connected: userId={}, sessionId={}", userId, sessionId);
            messagingTemplate.convertAndSend("/topic/presence",
                    (Object) Map.of("userId", userId, "online", true));
        }
    }

    @EventListener
    public void handleWebSocketDisconnect(SessionDisconnectEvent event) {
        StompHeaderAccessor accessor = StompHeaderAccessor.wrap(event.getMessage());
        Principal principal = accessor.getUser();
        if (principal != null) {
            Long userId = Long.valueOf(principal.getName());
            sessionService.removeUser(userId);
            log.info("WebSocket disconnected: userId={}", userId);
            messagingTemplate.convertAndSend("/topic/presence",
                    (Object) Map.of("userId", userId, "online", false));
        }
    }
}
