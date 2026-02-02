package com.mg.chat_app.controller;

import java.security.Principal;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.mg.chat_app.dto.ChatMessageDto;
import com.mg.chat_app.dto.SendMessageRequest;
import com.mg.chat_app.dto.UserDto;
import com.mg.chat_app.entity.Message;
import com.mg.chat_app.entity.Presence;
import com.mg.chat_app.model.MessageStatus;
import com.mg.chat_app.repository.MessageRepository;
import com.mg.chat_app.repository.UserRepository;
import com.mg.chat_app.repository.PresenceRepository;
import com.mg.chat_app.service.ChatService;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api/chat")
@RequiredArgsConstructor
public class ChatRestController {

    private final MessageRepository messageRepository;
    private final UserRepository userRepository;
    private final ChatService chatService;
    private final PresenceRepository presenceRepository;

    @GetMapping("/users")
    public List<UserDto> listUsers(Principal principal) {
        Long currentId = Long.valueOf(principal.getName());
        return userRepository.findAll().stream()
                .filter(u -> !u.getUserId().equals(currentId))
                .map(u -> new UserDto(u.getUserId(), u.getUsername()))
                .toList();
    }

    @PostMapping("/send")
    public ChatMessageDto sendMessage(@Valid @RequestBody SendMessageRequest req, Principal principal) {
        Long senderId = Long.valueOf(principal.getName());
        Message msg = Message.builder()
                .senderId(senderId)
                .receiverId(req.getReceiverId())
                .content(req.getContent())
                .build();
        return chatService.sendMessage(msg);
    }

    @Transactional
    @GetMapping("/offline")
    public List<Message> fetchOffline(Principal principal) {
        Long userId = Long.valueOf(principal.getName());
        List<Message> messages = messageRepository.findByReceiverIdAndStatus(userId, MessageStatus.SENT);
        messages.forEach(m -> m.setStatus(MessageStatus.DELIVERED));
        messageRepository.saveAll(messages);
        return messages;
    }

    @GetMapping("/history")
    public Page<Message> getHistory(@RequestParam Long otherUserId,
                                    @RequestParam(defaultValue = "0") int page,
                                    @RequestParam(defaultValue = "50") int size,
                                    Principal principal) {
        Long userId = Long.valueOf(principal.getName());
        return messageRepository.findConversation(userId, otherUserId,
                PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt")));
    }

    @GetMapping("/presence")
    public Map<Long, Boolean> getPresence(Principal principal) {
        Long currentId = Long.valueOf(principal.getName());
        Map<Long, Boolean> presenceMap = new HashMap<>();
        presenceRepository.findAll()
                .forEach(p -> {
                    if (!p.getUserId().equals(currentId)) {
                        presenceMap.put(p.getUserId(), Boolean.TRUE.equals(p.getIsOnline()));
                    }
                });
        // Include users without a presence record as offline
        userRepository.findAll().stream()
                .filter(u -> !u.getUserId().equals(currentId) && !presenceMap.containsKey(u.getUserId()))
                .forEach(u -> presenceMap.put(u.getUserId(), false));
        return presenceMap;
    }

    @GetMapping("/unread-counts")
    public Map<Long, Long> getUnreadCounts(Principal principal) {
        Long userId = Long.valueOf(principal.getName());
        Map<Long, Long> counts = new HashMap<>();
        messageRepository.countUnreadBySender(userId, List.of(MessageStatus.SENT, MessageStatus.DELIVERED))
                .forEach(row -> counts.put((Long) row[0], (Long) row[1]));
        return counts;
    }

    @Transactional
    @PostMapping("/heartbeat")
    public Map<String, Boolean> heartbeat(Principal principal) {
        Long userId = Long.valueOf(principal.getName());
        Presence presence = presenceRepository.findById(userId)
                .orElse(Presence.builder().userId(userId).build());
        presence.setIsOnline(true);
        presence.setLastSeen(LocalDateTime.now());
        presenceRepository.save(presence);
        return Map.of("ok", true);
    }

    @Transactional
    @PostMapping("/go-offline")
    public Map<String, Boolean> goOffline(Principal principal) {
        Long userId = Long.valueOf(principal.getName());
        presenceRepository.findById(userId).ifPresent(p -> {
            p.setIsOnline(false);
            p.setLastSeen(LocalDateTime.now());
            presenceRepository.save(p);
        });
        return Map.of("ok", true);
    }
}
