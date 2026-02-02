package com.mg.chat_app.repository;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.mg.chat_app.entity.User;

public interface UserRepository extends JpaRepository<User, Long> {

    Optional<User> findByUsername(String username);
}

