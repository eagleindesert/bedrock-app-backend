package com.bedrock.app.auth.dto.response;

import com.bedrock.app.auth.domain.user.User;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Getter;

import java.time.LocalDateTime;

@Schema(description = "내 정보 응답")
@Getter
@Builder
public class MeResponse {

    @Schema(description = "사용자 ID", example = "1")
    private Long id;

    @Schema(description = "사용자 이메일", example = "test@example.com")
    private String email;

    @Schema(description = "닉네임", example = "테스터")
    private String nickname;

    @Schema(description = "권한", example = "USER")
    private String role;

    @Schema(description = "프로필 이미지 URL", example = "https://cdn.example.com/profile/1.png")
    private String profileImageUrl;

    @Schema(description = "가입 일시", example = "2026-06-24T01:55:00")
    private LocalDateTime createdAt;

    @Schema(description = "마지막 로그인 일시", example = "2026-08-12T10:30:00")
    private LocalDateTime lastLoginAt;

    public static MeResponse from(User user) {
        return MeResponse.builder()
                .id(user.getId())
                .email(user.getEmail())
                .nickname(user.getNickname())
                .role(user.getRole().name())
                .profileImageUrl(user.getProfileImageUrl())
                .createdAt(user.getCreatedAt())
                .lastLoginAt(user.getLastLoginAt())
                .build();
    }
}
