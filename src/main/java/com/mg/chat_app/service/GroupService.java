package com.mg.chat_app.service;

import java.util.List;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.mg.chat_app.dto.GroupDto;
import com.mg.chat_app.entity.ChatGroup;
import com.mg.chat_app.entity.GroupMember;
import com.mg.chat_app.entity.User;
import com.mg.chat_app.model.GroupRole;
import com.mg.chat_app.repository.ChatGroupRepository;
import com.mg.chat_app.repository.GroupMemberRepository;
import com.mg.chat_app.repository.UserRepository;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class GroupService {

    private final ChatGroupRepository groupRepository;
    private final GroupMemberRepository memberRepository;
    private final UserRepository userRepository;

    @Transactional
    public ChatGroup createGroup(String name, Long creatorId, List<Long> memberIds) {
        ChatGroup group = ChatGroup.builder()
                .name(name)
                .createdBy(creatorId)
                .build();
        group = groupRepository.save(group);

        // Add creator as ADMIN
        memberRepository.save(GroupMember.builder()
                .groupId(group.getGroupId())
                .userId(creatorId)
                .role(GroupRole.ADMIN)
                .build());

        // Add other members
        for (Long memberId : memberIds) {
            if (!memberId.equals(creatorId)) {
                memberRepository.save(GroupMember.builder()
                        .groupId(group.getGroupId())
                        .userId(memberId)
                        .role(GroupRole.MEMBER)
                        .build());
            }
        }

        return group;
    }

    @Transactional
    public void addMember(Long groupId, Long userId, Long requesterId) {
        validateAdmin(groupId, requesterId);
        if (memberRepository.existsByGroupIdAndUserId(groupId, userId)) {
            throw new IllegalArgumentException("User is already a member of this group");
        }
        memberRepository.save(GroupMember.builder()
                .groupId(groupId)
                .userId(userId)
                .role(GroupRole.MEMBER)
                .build());
    }

    @Transactional
    public void removeMember(Long groupId, Long userId, Long requesterId) {
        validateAdmin(groupId, requesterId);
        memberRepository.deleteByGroupIdAndUserId(groupId, userId);
    }

    public List<GroupDto> getGroupsForUser(Long userId) {
        List<GroupMember> memberships = memberRepository.findByUserId(userId);
        return memberships.stream()
                .map(m -> {
                    ChatGroup group = groupRepository.findById(m.getGroupId())
                            .orElseThrow(() -> new RuntimeException("Group not found"));
                    return toDto(group);
                })
                .collect(Collectors.toList());
    }

    public List<Long> getGroupMemberIds(Long groupId) {
        return memberRepository.findByGroupId(groupId).stream()
                .map(GroupMember::getUserId)
                .collect(Collectors.toList());
    }

    public boolean isMember(Long groupId, Long userId) {
        return memberRepository.existsByGroupIdAndUserId(groupId, userId);
    }

    public GroupDto toDto(ChatGroup group) {
        List<GroupMember> members = memberRepository.findByGroupId(group.getGroupId());
        List<GroupDto.GroupMemberDto> memberDtos = members.stream()
                .map(m -> {
                    String username = userRepository.findById(m.getUserId())
                            .map(User::getUsername)
                            .orElse("Unknown");
                    return GroupDto.GroupMemberDto.builder()
                            .userId(m.getUserId())
                            .username(username)
                            .role(m.getRole().name())
                            .build();
                })
                .collect(Collectors.toList());

        return GroupDto.builder()
                .groupId(group.getGroupId())
                .name(group.getName())
                .createdBy(group.getCreatedBy())
                .createdAt(group.getCreatedAt())
                .members(memberDtos)
                .build();
    }

    private void validateAdmin(Long groupId, Long userId) {
        List<GroupMember> members = memberRepository.findByGroupId(groupId);
        boolean isAdmin = members.stream()
                .anyMatch(m -> m.getUserId().equals(userId) && m.getRole() == GroupRole.ADMIN);
        if (!isAdmin) {
            throw new SecurityException("Only group admins can perform this action");
        }
    }
}
