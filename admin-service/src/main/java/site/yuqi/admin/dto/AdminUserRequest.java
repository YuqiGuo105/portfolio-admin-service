package site.yuqi.admin.dto;

public record AdminUserRequest(
        String email,
        String role,
        String status,
        String displayName,
        String note
) {}
