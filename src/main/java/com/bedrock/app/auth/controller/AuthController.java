package com.bedrock.app.auth.controller;

import com.bedrock.app.auth.domain.user.User;
import com.bedrock.app.auth.dto.request.LoginRequest;
import com.bedrock.app.auth.dto.request.SignupRequest;
import com.bedrock.app.auth.dto.response.MeResponse;
import com.bedrock.app.auth.service.AuthService;
import com.bedrock.app.common.exception.ErrorResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Tag(
        name = "Auth",
        description = "세션 기반 인증 API. 로그인 성공 시 SESSION 쿠키가 발급되며, "
                + "이후 인증이 필요한 요청은 이 쿠키를 함께 전송한다. CSRF는 비활성화되어 있다."
)
@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;

    @Operation(
            summary = "회원가입",
            description = "이메일/비밀번호/닉네임으로 사용자를 생성한다. 인증이 필요 없다."
    )
    @ApiResponse(responseCode = "200", description = "가입 성공 (본문 없음)")
    @ApiResponse(responseCode = "400", description = "이미 사용 중인 이메일이거나 요청 값 검증 실패",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @SecurityRequirements
    @PostMapping("/signup")
    public ResponseEntity<Void> signup(@Valid @RequestBody SignupRequest request) {
        authService.signup(request);
        return ResponseEntity.ok().build();
    }

    @Operation(
            summary = "로그인",
            description = "인증에 성공하면 `Set-Cookie: SESSION=...` 헤더로 세션 쿠키가 발급된다. "
                    + "Swagger UI 에서 이 요청을 실행하면 브라우저가 쿠키를 저장하므로 "
                    + "이후 다른 API 는 별도 설정 없이 호출할 수 있다."
    )
    @ApiResponse(responseCode = "200", description = "로그인 성공 (세션 쿠키 발급)")
    @ApiResponse(responseCode = "400", description = "이메일 또는 비밀번호가 올바르지 않음",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @SecurityRequirements
    @PostMapping("/login")
    public ResponseEntity<Void> login(@Valid @RequestBody LoginRequest request, HttpServletRequest httpRequest) {
        User user = authService.login(request);
        
        // 인증 객체 생성
        UsernamePasswordAuthenticationToken auth = new UsernamePasswordAuthenticationToken(
                user.getId(), 
                null, 
                List.of(new SimpleGrantedAuthority("ROLE_" + user.getRole().name()))
        );
        
        // Security Context에 설정
        SecurityContext sc = SecurityContextHolder.getContext();
        sc.setAuthentication(auth);
        
        // 세션에 Security Context 저장 (Spring Security 6 이상에서 명시적 저장 권장)
        HttpSession session = httpRequest.getSession(true);
        session.setAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY, sc);

        // 디버깅/가시성용: userId 를 세션에 단순 값으로 직접 저장 (인증에는 사용 안 함)
        // → Redis 뷰어에서 sessionAttr:userId 필드로 값이 그대로 보임
        session.setAttribute("userId", user.getId());

        return ResponseEntity.ok().build();
    }

    @Operation(
            summary = "내 정보 조회",
            description = "세션 쿠키로 인증된 현재 사용자의 정보를 반환한다."
    )
    @ApiResponse(responseCode = "200", description = "조회 성공")
    @ApiResponse(responseCode = "401", description = "인증되지 않음", content = @Content)
    @GetMapping("/me")
    public ResponseEntity<MeResponse> getMe() {
        Object principal = SecurityContextHolder.getContext().getAuthentication().getPrincipal();
        if (principal instanceof Long userId) {
            MeResponse response = authService.getMe(userId);
            return ResponseEntity.ok(response);
        }
        return ResponseEntity.status(401).build();
    }

    @Operation(
            summary = "로그아웃",
            description = "현재 세션을 무효화한다. 요청 본문은 없다."
    )
    @ApiResponse(responseCode = "200", description = "로그아웃 성공 (세션 무효화)")
    @ApiResponse(responseCode = "401", description = "인증되지 않음", content = @Content)
    @PostMapping("/logout")
    public ResponseEntity<Void> logout(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        if (session != null) {
            session.invalidate();
        }
        SecurityContextHolder.clearContext();
        return ResponseEntity.ok().build();
    }

    @Operation(
            summary = "회원 탈퇴",
            description = "현재 사용자를 Soft Delete 처리하고 세션을 무효화한다. 요청 본문은 없다."
    )
    @ApiResponse(responseCode = "200", description = "탈퇴 성공 (Soft Delete + 세션 무효화)")
    @ApiResponse(responseCode = "401", description = "인증되지 않음", content = @Content)
    @DeleteMapping("/withdraw")
    public ResponseEntity<Void> withdraw(HttpServletRequest request) {
        Object principal = SecurityContextHolder.getContext().getAuthentication().getPrincipal();
        if (principal instanceof Long userId) {
            authService.withdraw(userId);
            
            HttpSession session = request.getSession(false);
            if (session != null) {
                session.invalidate();
            }
            SecurityContextHolder.clearContext();
        }
        return ResponseEntity.ok().build();
    }
}
