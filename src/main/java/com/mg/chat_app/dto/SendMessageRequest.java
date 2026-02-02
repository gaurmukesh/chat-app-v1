package com.mg.chat_app.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class SendMessageRequest {

    @NotNull
    private Long receiverId;

    @NotBlank
    @Size(max = 5000)
    private String content;
}
