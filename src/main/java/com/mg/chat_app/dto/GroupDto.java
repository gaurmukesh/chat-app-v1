package com.mg.chat_app.dto;

import java.time.LocalDateTime;
import java.util.List;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class GroupDto {
    private Long groupId;
    private String name;
    private Long createdBy;
    private LocalDateTime createdAt;
    private List<GroupMemberDto> members;

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class GroupMemberDto {
        private Long userId;
        private String username;
        private String role;
    }
}
