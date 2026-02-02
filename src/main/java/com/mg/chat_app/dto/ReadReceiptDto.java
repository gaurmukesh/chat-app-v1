package com.mg.chat_app.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data                   // generates getters, setters, toString, equals, hashCode
@NoArgsConstructor      // generates no-args constructor
@AllArgsConstructor     // generates all-args constructor
public class ReadReceiptDto {
    private Long messageId;
    private Long senderId;
    private Long receiverId;
    private String status;   // e.g. "READ"
}

