package com.mg.chat_app.repository;



import org.springframework.data.jpa.repository.JpaRepository;

import com.mg.chat_app.entity.Presence;

public interface PresenceRepository extends JpaRepository<Presence, Long> {
}
