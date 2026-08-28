package com.bedrock.app.auth.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import lombok.Getter;

@Schema(description = "회원가입 요청")
@Getter
public class SignupRequest {

    @Schema(description = "사용자 이메일", example = "test@example.com")
    @NotBlank(message = "이메일은 필수입니다.")
    @Email(message = "올바른 이메일 형식이어야 합니다.")
    private String email;

    @Schema(description = "비밀번호", example = "password1234")
    @NotBlank(message = "비밀번호는 필수입니다.")
    private String password;

    @Schema(description = "닉네임", example = "테스터")
    @NotBlank(message = "닉네임은 필수입니다.")
    private String nickname;
}
