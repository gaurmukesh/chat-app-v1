package com.mg.chat_app.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import com.mg.chat_app.entity.ChatGroup;

public interface ChatGroupRepository extends JpaRepository<ChatGroup, Long> {
}
