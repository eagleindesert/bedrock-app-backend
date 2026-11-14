package com.bedrock.app.auth.api;

import com.bedrock.app.auth.dto.AuthDtos;
import com.bedrock.app.auth.service.AuthSessions;
import com.bedrock.app.auth.service.EmailAuthentication;
import com.bedrock.app.auth.service.NotMeService;
import com.bedrock.app.auth.service.OAuthAuthentication;
import com.bedrock.app.auth.service.PublicAuthTiming;
import com.bedrock.app.common.auth.CurrentUser;
import com.bedrock.app.common.auth.SessionPrincipal;
import com.bedrock.app.common.response.ApiResponse;
import com.bedrock.app.mail.service.MailCodeService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController("be1AuthController")
@RequestMapping("/api/v1/auth")
@Tag(name = "계정 인증", description = "메일 확인·OAuth·Redis 세션·기기별 로그아웃")
public class AuthController {
    private final EmailAuthentication email;
    private final OAuthAuthentication oauth;
    private final AuthSessions sessions;
    private final NotMeService notMe;
    private final PublicAuthTiming timing;
    public AuthController(EmailAuthentication email, OAuthAuthentication oauth, AuthSessions sessions, NotMeService notMe, PublicAuthTiming timing) {
        this.email = email; this.oauth = oauth; this.sessions = sessions; this.notMe = notMe;
        this.timing = timing;
    }

    @GetMapping("/session")
    @Operation(summary = "현재 세션 확인", description = "익명 세션도 생성한다. 확인 요청만으로 일반 웹의 사용자 활동 만료를 연장하지 않는다.")
    public AuthDtos.SessionDto session(HttpServletRequest request) { return sessions.view(request); }

    @GetMapping("/csrf")
    @Operation(summary = "CSRF 토큰 조회", description = "쿠키와 함께 쓰기 요청 헤더에 전달한다. 로그인으로 세션이 바뀌면 다시 조회한다.")
    public CsrfDto csrf(CsrfToken token) { return new CsrfDto(token.getHeaderName(), token.getToken()); }

    @PostMapping("/signup") @ResponseStatus(HttpStatus.ACCEPTED)
    @Operation(summary = "이메일 가입 시작", description = "기존 계정 여부와 같은 응답으로 메일 확인 단계를 시작한다. 비밀번호는 코드 확인 뒤 받는다.")
    public MailCodeService.Challenge signup(@Valid @RequestBody AuthDtos.SignupRequest input, HttpServletRequest request) { return timing.execute(() -> email.signup(input, request)); }

    @PostMapping("/login")
    @Operation(summary = "이메일 로그인", description = "실패와 잠금을 같은 오류로 반환한다. 탈퇴 유예 계정에는 먼저 메일 확인을 요구한다.")
    public AuthDtos.AuthStep login(@Valid @RequestBody AuthDtos.LoginRequest input, HttpServletRequest request, HttpServletResponse response) { return timing.execute(() -> email.login(input, request, response)); }

    @PostMapping("/password-reset") @ResponseStatus(HttpStatus.ACCEPTED)
    @Operation(summary = "비밀번호 재설정 시작", description = "계정 존재 여부와 무관하게 같은 응답을 반환한다. 변경 완료 시 모든 기기의 세션을 종료한다.")
    public MailCodeService.Challenge reset(@Valid @RequestBody AuthDtos.PasswordResetRequest input, HttpServletRequest request) { return timing.execute(() -> email.reset(input, request)); }

    @PostMapping("/mail-codes/complete")
    @Operation(summary = "메일 확인과 인증 작업 완료", description = "같은 세션의 목적에 맞는 코드를 먼저 검증한다. 비밀번호 검증은 그 뒤에만 수행한다.")
    public AuthDtos.AuthStep completeMail(@Valid @RequestBody AuthDtos.MailCodeRequest input, HttpServletRequest request, HttpServletResponse response) {
        return oauth.hasFlow(request) ? oauth.completeMail(input, request, response) : email.complete(input, request, response);
    }

    @PostMapping("/mail-codes/resend") @ResponseStatus(HttpStatus.ACCEPTED)
    @Operation(summary = "메일 코드 재발송", description = "60초 간격과 발송 한도를 검사하고 이전 코드를 무효화한다.")
    public MailCodeService.Challenge resend(HttpServletRequest request) { return oauth.hasFlow(request) ? oauth.resend(request) : email.resend(request); }

    @PostMapping("/account-recovery")
    @Operation(summary = "탈퇴 유예 계정 복구 또는 새로 시작", description = "메일로 확인한 일회용 권한이 필요하다. 새로 시작은 기존 데이터와 키를 파기한다.")
    public AuthDtos.AuthStep recovery(@Valid @RequestBody AuthDtos.RecoveryRequest input, HttpServletRequest request, HttpServletResponse response) {
        return oauth.hasFlow(request) ? oauth.recover(input, request, response) : email.recovery(input, request, response);
    }

    @PostMapping("/oauth/{provider}/start")
    @Operation(summary = "Google·Apple 인증 시작", description = "서버가 만든 nonce·state를 현재 세션에 묶는다. 허용된 리다이렉트 주소만 사용한다.")
    public AuthDtos.OAuthChallenge startOAuth(@PathVariable AuthDtos.Provider provider, @Valid @RequestBody AuthDtos.OAuthStartRequest input, HttpServletRequest request) { return oauth.start(provider, input, request); }

    @PostMapping("/oauth/{provider}/complete")
    @Operation(summary = "제공자 자격 검증·로그인", description = "서명·발급자·대상·만료·nonce·state를 검사하고 첫 연결의 이메일 소유 확인을 수행한다.")
    public AuthDtos.AuthStep completeOAuth(@PathVariable AuthDtos.Provider provider, @Valid @RequestBody AuthDtos.OAuthCompleteRequest input,
            HttpServletRequest request, HttpServletResponse response) { return oauth.complete(provider, input, request, response); }

    @PostMapping("/logout")
    @Operation(summary = "현재 기기 로그아웃", description = "서버 세션을 무효화한다. 보내지 않은 쓰기 정리는 호출 전에 클라이언트가 확인한다.")
    public ApiResponse<Void> logout(HttpServletRequest request) { sessions.signOut(request); return ApiResponse.ok(); }

    @GetMapping("/devices")
    @Operation(summary = "로그인한 기기 목록", description = "플랫폼·표시용 모델·마지막 활동·현재 기기 여부만 반환한다.")
    public List<AuthDtos.DeviceDto> devices(@CurrentUser SessionPrincipal user) { return sessions.devices(user); }

    @DeleteMapping("/devices/{deviceId}")
    @Operation(summary = "기기 하나 끊기", description = "요청자 소유 기기의 세션을 종료한다. 종료된 기기의 다음 요청은 DEVICE_REVOKED를 받는다.")
    public ApiResponse<Void> revoke(@CurrentUser SessionPrincipal user, @PathVariable UUID deviceId, HttpServletRequest request) {
        sessions.revoke(user, deviceId, false);
        if (user.deviceId().equals(deviceId)) sessions.signOut(request);
        return ApiResponse.ok();
    }

    @DeleteMapping("/devices")
    @Operation(summary = "다른 기기 모두 끊기", description = "현재 기기를 제외하고 계정의 모든 세션을 종료한다.")
    public ApiResponse<Void> revokeOthers(@CurrentUser SessionPrincipal user) { sessions.revoke(user, null, true); return ApiResponse.ok(); }

    @PostMapping("/not-me/inspect")
    @Operation(summary = "내가 아니다 확인 정보", description = "토큰을 소비하지 않고 제공자·만료 시각만 확인한다.")
    public AuthDtos.NotMeInfo inspect(@Valid @RequestBody AuthDtos.NotMeRequest input) { return notMe.inspect(input.token()); }

    @PostMapping("/not-me/revoke")
    @Operation(summary = "새 제공자 연결 해제", description = "명시적 확인 뒤 7일 이내 일회용 토큰을 소비하고 모든 기기 세션을 종료한다.")
    public ApiResponse<Void> notMe(@Valid @RequestBody AuthDtos.NotMeRequest input) { notMe.revoke(input); return ApiResponse.ok(); }

    public record CsrfDto(String headerName, String token) {}
}
