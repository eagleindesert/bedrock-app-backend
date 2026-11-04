package com.bedrock.app.common.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import io.swagger.v3.oas.models.servers.Server;
import org.springdoc.core.utils.SpringDocUtils;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import com.bedrock.app.common.auth.CurrentUser;

import java.util.List;

@Configuration
public class OpenApiConfig {

    /** 세션 쿠키 인증 스킴 이름 (components.securitySchemes 키) */
    public static final String SESSION_AUTH = "sessionAuth";

    static {
        // 인증 주체는 클라이언트가 전달하는 쿼리 파라미터가 아니다.
        SpringDocUtils.getConfig()
                .addAnnotationsToIgnore(AuthenticationPrincipal.class, CurrentUser.class);
    }

    @Bean
    public OpenAPI bedrockOpenAPI() {
        Info info = new Info()
                .title("Bedrock App API")
                .version("v2-common")
                .description("""
                        Bedrock 백엔드 REST API 문서.

                        인증은 BEDROCK_SESSION 쿠키 방식이다.
                        쓰기 요청 전 GET /api/auth/csrf의 data.token을 X-CSRF-TOKEN 헤더에 넣는다.
                        현재는 공통 기반 단계이며 가입·로그인·업무 API는 담당 기능 구현에서 추가한다.
                        BE 1은 인증 성공 후 SessionAuthenticationService를 호출하고 CSRF 토큰을 교체한다.
                        웹 탭은 X-Bedrock-Account-Id 헤더로 현재 계정 UUID를 전달한다.
                        JSON 응답은 success/data/error 형식이며 204 응답에는 본문이 없다.
                        """);

        SecurityScheme sessionCookie = new SecurityScheme()
                .type(SecurityScheme.Type.APIKEY)
                .in(SecurityScheme.In.COOKIE)
                .name("BEDROCK_SESSION")
                .description("로그인 성공 시 발급되는 세션 쿠키");

        return new OpenAPI()
                .info(info)
                .servers(List.of(
                        new Server()
                                .url("http://localhost:8080")
                                .description("로컬 개발 서버")
                ))
                .components(new Components()
                        .addSecuritySchemes(SESSION_AUTH, sessionCookie))
                .addSecurityItem(new SecurityRequirement().addList(SESSION_AUTH));
    }
}
