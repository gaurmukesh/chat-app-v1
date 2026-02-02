package com.mg.chat_app.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

@Entity
@Table(name = "presence")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Presence {

	@Id
	@Column(name = "user_id")
	private Long userId;
	@Column(name = "is_online")
	private Boolean isOnline;
	@Column(name = "last_seen")
	private LocalDateTime lastSeen;
}
