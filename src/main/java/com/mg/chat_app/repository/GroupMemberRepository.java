package com.mg.chat_app.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.mg.chat_app.entity.GroupMember;

public interface GroupMemberRepository extends JpaRepository<GroupMember, Long> {

    List<GroupMember> findByGroupId(Long groupId);

    List<GroupMember> findByUserId(Long userId);

    boolean existsByGroupIdAndUserId(Long groupId, Long userId);

    void deleteByGroupIdAndUserId(Long groupId, Long userId);
}
