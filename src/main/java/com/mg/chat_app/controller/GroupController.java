package com.mg.chat_app.controller;

import java.security.Principal;
import java.util.List;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.mg.chat_app.dto.ChatMessageDto;
import com.mg.chat_app.dto.CreateGroupRequest;
import com.mg.chat_app.dto.GroupDto;
import com.mg.chat_app.dto.GroupMessageRequest;
import com.mg.chat_app.entity.ChatGroup;
import com.mg.chat_app.entity.Message;
import com.mg.chat_app.kafka.ChatMessageProducer;
import com.mg.chat_app.model.MessageStatus;
import com.mg.chat_app.model.MessageType;
import com.mg.chat_app.repository.MessageRepository;
import com.mg.chat_app.service.GroupService;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api/groups")
@RequiredArgsConstructor
public class GroupController {

    private final GroupService groupService;
    private final MessageRepository messageRepository;
    private final ChatMessageProducer producer;

    @PostMapping
    public GroupDto createGroup(@Valid @RequestBody CreateGroupRequest req, Principal principal) {
        Long userId = Long.valueOf(principal.getName());
        ChatGroup group = groupService.createGroup(req.getName(), userId, req.getMemberIds());
        return groupService.toDto(group);
    }

    @GetMapping
    public List<GroupDto> getMyGroups(Principal principal) {
        Long userId = Long.valueOf(principal.getName());
        return groupService.getGroupsForUser(userId);
    }

    @PostMapping("/{groupId}/members")
    public void addMember(@PathVariable Long groupId,
                          @RequestParam Long userId,
                          Principal principal) {
        Long requesterId = Long.valueOf(principal.getName());
        groupService.addMember(groupId, userId, requesterId);
    }

    @DeleteMapping("/{groupId}/members/{userId}")
    public void removeMember(@PathVariable Long groupId,
                             @PathVariable Long userId,
                             Principal principal) {
        Long requesterId = Long.valueOf(principal.getName());
        groupService.removeMember(groupId, userId, requesterId);
    }

    @PostMapping("/{groupId}/messages")
    public ChatMessageDto sendGroupMessage(@PathVariable Long groupId,
                                           @Valid @RequestBody GroupMessageRequest req,
                                           Principal principal) {
        Long senderId = Long.valueOf(principal.getName());
        if (!groupService.isMember(groupId, senderId)) {
            throw new SecurityException("Not a member of this group");
        }

        Message msg = Message.builder()
                .senderId(senderId)
                .content(req.getContent())
                .groupId(groupId)
                .messageType(MessageType.GROUP)
                .status(MessageStatus.SENT)
                .build();
        msg = messageRepository.save(msg);

        ChatMessageDto dto = new ChatMessageDto(
                msg.getMessageId(), msg.getSenderId(), null, msg.getContent(), groupId);
        producer.publishGroupMessage(dto);

        return dto;
    }

    @GetMapping("/{groupId}/messages")
    public Page<Message> getGroupMessages(@PathVariable Long groupId,
                                          @RequestParam(defaultValue = "0") int page,
                                          @RequestParam(defaultValue = "50") int size,
                                          Principal principal) {
        Long userId = Long.valueOf(principal.getName());
        if (!groupService.isMember(groupId, userId)) {
            throw new SecurityException("Not a member of this group");
        }
        return messageRepository.findByGroupIdOrderByCreatedAtDesc(groupId,
                PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt")));
    }
}
