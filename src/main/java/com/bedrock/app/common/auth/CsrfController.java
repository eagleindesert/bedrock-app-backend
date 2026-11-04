package com.bedrock.app.common.auth;

import com.bedrock.app.common.response.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth")
@Tag(name = "Session", description = "공통 세션·CSRF 기반. 실제 가입·인증 기능은 BE 1에서 구현한다.")
public class CsrfController {
    @GetMapping("/csrf")
    @SecurityRequirements
    @Operation(summary = "CSRF 토큰 조회", description = "쓰기 전 쿠키와 함께 보관하며 로그인 후 다시 조회한다.")
    public ApiResponse<CsrfResponse> csrf(CsrfToken token) {
        return ApiResponse.ok(new CsrfResponse(token.getHeaderName(), token.getToken()));
    }

    public record CsrfResponse(String headerName, String token) {
    }
}
