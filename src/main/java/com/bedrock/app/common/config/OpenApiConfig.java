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

import java.util.List;

@Configuration
public class OpenApiConfig {

    /** 세션 쿠키 인증 스킴 이름 (components.securitySchemes 키) */
    public static final String SESSION_AUTH = "sessionAuth";

    static {
        // @AuthenticationPrincipal Long ownerId 가 쿼리 파라미터로 문서화되는 것을 방지
        SpringDocUtils.getConfig()
                .addAnnotationsToIgnore(AuthenticationPrincipal.class);
    }

    @Bean
    public OpenAPI bedrockOpenAPI() {
        Info info = new Info()
                .title("Bedrock App API")
                .version("v1")
                .description("""
                        Bedrock 백엔드 REST API 문서.

                        인증은 세션 쿠키(SESSION) 방식이다.
                        `POST /api/auth/login` 을 먼저 실행하면 브라우저에 세션 쿠키가 저장되고,
                        이후 다른 API 는 별도 설정 없이 그대로 호출할 수 있다.
                        """);

        SecurityScheme sessionCookie = new SecurityScheme()
                .type(SecurityScheme.Type.APIKEY)
                .in(SecurityScheme.In.COOKIE)
                .name("SESSION")
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
