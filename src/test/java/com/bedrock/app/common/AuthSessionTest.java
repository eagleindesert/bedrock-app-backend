package com.bedrock.app.common;

import com.bedrock.app.common.auth.CsrfController;
import com.bedrock.app.common.auth.SessionAuthenticationService;
import com.bedrock.app.common.auth.SessionPrincipal;
import com.bedrock.app.common.config.CorsConfig;
import com.bedrock.app.common.config.SecurityConfig;
import com.bedrock.app.common.error.ApiErrorWriter;
import com.bedrock.app.common.response.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.ObjectMapper;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(controllers = {CsrfController.class, AuthSessionTest.TestAuthenticationController.class},
        properties = "bedrock.cors.allowed-origins=https://app.example.com")
@Import({SecurityConfig.class, CorsConfig.class, ApiErrorWriter.class, SessionAuthenticationService.class,
        AuthSessionTest.TestAuthenticationController.class})
class AuthSessionTest {
    private static final UUID USER_ID = UUID.randomUUID();

    @Autowired
    private MockMvc mvc;
    @Autowired
    private ObjectMapper mapper;

    @Test
    void signInRotatesSessionAndCsrfAndSignOutInvalidatesSession() throws Exception {
        var tokenResult = mvc.perform(get("/api/auth/csrf")).andExpect(status().isOk()).andReturn();
        var session = (MockHttpSession) tokenResult.getRequest().getSession(false);
        String oldSessionId = session.getId();
        String oldToken = mapper.readTree(tokenResult.getResponse().getContentAsString()).at("/data/token").asString();

        mvc.perform(post("/api/auth/login").session(session).header("X-CSRF-TOKEN", oldToken))
                .andExpect(status().isOk()).andExpect(jsonPath("$.success").value(true));
        assertThat(session.getId()).isNotEqualTo(oldSessionId);
        assertThat(session.getAttribute(SessionPrincipal.SESSION_ATTRIBUTE))
                .isInstanceOfSatisfying(SessionPrincipal.class, principal -> assertThat(principal.userId()).isEqualTo(USER_ID));
        mvc.perform(post("/api/auth/logout").session(session).header("X-CSRF-TOKEN", oldToken))
                .andExpect(status().isForbidden());

        var nextTokenResult = mvc.perform(get("/api/auth/csrf").session(session)).andReturn();
        String nextToken = mapper.readTree(nextTokenResult.getResponse().getContentAsString()).at("/data/token").asString();
        assertThat(nextToken).isNotEqualTo(oldToken);
        mvc.perform(post("/api/auth/logout").session(session).header("X-CSRF-TOKEN", nextToken))
                .andExpect(status().isOk());
        assertThat(session.isInvalid()).isTrue();
    }

    /** 인증 완료 후 호출되는 공통 세션 연산만 검증하는 테스트 전용 컨트롤러. */
    @RestController
    static class TestAuthenticationController {
        private final SessionAuthenticationService sessions;

        TestAuthenticationController(SessionAuthenticationService sessions) {
            this.sessions = sessions;
        }

        @PostMapping("/api/auth/login")
        ApiResponse<Void> signIn(HttpServletRequest request, HttpServletResponse response) {
            sessions.signIn(new SessionPrincipal(USER_ID, UUID.randomUUID(), "WEB", false), request, response);
            return ApiResponse.ok();
        }

        @PostMapping("/api/auth/logout")
        ApiResponse<Void> signOut(HttpServletRequest request) {
            sessions.signOut(request);
            return ApiResponse.ok();
        }
    }
}
