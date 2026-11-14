package com.bedrock.app.auth.dto;

import com.bedrock.app.common.validation.IanaTimeZone;
import com.bedrock.app.mail.service.MailCodeService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.io.Serializable;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class AuthDtos {
    private AuthDtos() {}

    public enum Platform { IOS, IPADOS, WEB }
    public enum Provider { GOOGLE, APPLE }
    public enum RecoveryAction { RESTORE, RESTART }
    public enum NextStep { MAIL_CODE_REQUIRED, AUTHENTICATED, RECOVERY_CHOICE_REQUIRED, PASSWORD_RESET_COMPLETED }
    public enum SessionEndReason { EXPIRED, PASSWORD_RESET, DEVICE_REVOKED, NOT_ME, ACCOUNT_DELETED, UNKNOWN }

    public record DeviceContext(@NotNull Platform platform, @Size(max = 100) String modelName,
            @IanaTimeZone String timeZone, @Size(max = 256) String deviceId) implements Serializable {
        public String initialTimeZone() { return timeZone == null ? "UTC" : timeZone; }
    }

    public record SignupRequest(@NotBlank @Email @Size(max = 320) String email,
            @NotBlank @Size(max = 100) String name, @NotNull @Valid DeviceContext device) {}
    public record LoginRequest(@NotBlank @Email @Size(max = 320) String email, @NotNull String password,
            boolean rememberMe, @NotNull @Valid DeviceContext device) {
        @Override public String toString() { return "LoginRequest[redacted]"; }
    }
    public record PasswordResetRequest(@NotBlank @Email @Size(max = 320) String email,
            @NotNull @Valid DeviceContext device) {}
    /** 비밀번호의 필수/길이 검증은 메일 코드 확인이 끝난 뒤 서비스에서 한다. */
    public record MailCodeRequest(@NotNull String code, String password) {
        @Override public String toString() { return "MailCodeRequest[redacted]"; }
    }
    public record RecoveryRequest(@NotNull RecoveryAction action, boolean confirmed, String name, String password) {
        @Override public String toString() { return "RecoveryRequest[redacted]"; }
    }
    public record ProfileRequest(@NotBlank @Size(max = 100) String name) {}
    public record OAuthStartRequest(@NotNull @Valid DeviceContext device, boolean rememberMe, String redirectUri) {}
    public record OAuthCompleteRequest(@NotBlank String idToken, String authorizationCode, String state, String nonce) {
        @Override public String toString() { return "OAuthCompleteRequest[redacted]"; }
    }
    public record NotMeRequest(@NotBlank String token, boolean confirmed) {
        @Override public String toString() { return "NotMeRequest[redacted]"; }
    }

    public record UserDto(UUID id, String email, String name, Instant createdAt) {}
    public record SessionDto(boolean authenticated, UserDto user, Platform platform, boolean rememberMe,
            Instant idleExpiresAt, SessionEndReason sessionEndReason) {}
    public record DeviceDto(UUID id, Platform platform, String modelName, Instant lastActiveAt, boolean isCurrent) {}
    public record AuthStep(NextStep nextStep, SessionDto session, MailCodeService.Challenge challenge,
            List<RecoveryAction> availableRecoveryActions) {
        public static AuthStep authenticated(SessionDto session) {
            return new AuthStep(NextStep.AUTHENTICATED, session, null, List.of());
        }
        public static AuthStep mail(MailCodeService.Challenge challenge) {
            return new AuthStep(NextStep.MAIL_CODE_REQUIRED, null, challenge, List.of());
        }
        public static AuthStep recovery(boolean reset) {
            return new AuthStep(NextStep.RECOVERY_CHOICE_REQUIRED, null, null,
                    reset ? List.of(RecoveryAction.RESTORE) : List.of(RecoveryAction.RESTORE, RecoveryAction.RESTART));
        }
        public static AuthStep passwordReset() {
            return new AuthStep(NextStep.PASSWORD_RESET_COMPLETED, null, null, List.of());
        }
    }
    public record AccountDeletion(Instant deletedAt, Instant purgeAfter) {}
    public record OAuthChallenge(Provider provider, String authorizationUrl, String nonce, String state, Instant expiresAt) {}
    public record NotMeInfo(Provider provider, Instant expiresAt, boolean canRevoke) {}
}
