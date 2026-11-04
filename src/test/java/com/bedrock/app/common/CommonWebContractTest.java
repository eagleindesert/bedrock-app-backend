package com.bedrock.app.common;

import com.bedrock.app.common.auth.CurrentUser;
import com.bedrock.app.common.auth.SessionPrincipal;
import com.bedrock.app.common.config.CorsConfig;
import com.bedrock.app.common.config.SecurityConfig;
import com.bedrock.app.common.error.ApiErrorWriter;
import com.bedrock.app.common.error.Be3ScheduleErrorCode;
import com.bedrock.app.common.error.BusinessException;
import com.bedrock.app.common.filter.EndpointAccessLogFilter;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(controllers = CommonWebContractTest.ContractController.class,
        properties = "bedrock.cors.allowed-origins=https://app.example.com")
@Import({CommonWebContractTest.ContractController.class, SecurityConfig.class, CorsConfig.class,
        ApiErrorWriter.class, EndpointAccessLogFilter.class})
class CommonWebContractTest {
    private static final UUID USER_ID = UUID.fromString("355ec08b-0883-4d0e-811d-a0eb36c1f98b");

    @Autowired
    private MockMvc mvc;

    @Test
    void anonymousRequestHasCommonErrorAndNoStore() throws Exception {
        mvc.perform(get("/api/contract/user"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error.code").value("UNAUTHORIZED"));
    }

    @Test
    void injectsSessionPrincipalAndWrapsResponseOnce() throws Exception {
        mvc.perform(get("/api/contract/user").with(user()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.userId").value(USER_ID.toString()))
                .andExpect(jsonPath("$.data.success").doesNotExist());
    }

    @Test
    void mismatchedMalformedAndDuplicateAccountHeadersAreRejected() throws Exception {
        for (String header : List.of(UUID.randomUUID().toString(), "not-a-uuid", "")) {
            mvc.perform(get("/api/contract/user").with(user()).header("X-Bedrock-Account-Id", header))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error.code").value("ACCOUNT_MISMATCH"));
        }
        mvc.perform(get("/api/contract/user").with(user())
                        .header("X-Bedrock-Account-Id", USER_ID.toString(), USER_ID.toString()))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/contract/write").with(user())
                        .header("X-Bedrock-Account-Id", UUID.randomUUID()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("ACCOUNT_MISMATCH"));
    }

    @Test
    void matchingAccountHeaderIsAccepted() throws Exception {
        mvc.perform(get("/api/contract/user").with(user()).header("X-Bedrock-Account-Id", USER_ID))
                .andExpect(status().isOk());
    }

    @Test
    void writingRequiresCsrfToken() throws Exception {
        mvc.perform(post("/api/contract/write").with(user())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"hello\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("ACCESS_DENIED"));
        mvc.perform(post("/api/contract/write").with(user()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"hello\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.name").value("hello"));
    }

    @Test
    void validationAndMalformedJsonNeverEchoRejectedValues() throws Exception {
        mvc.perform(post("/api/contract/write").with(user()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.details[0].field").value("name"));
        mvc.perform(post("/api/contract/write").with(user()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{secret-password-invalid-json"))
                .andExpect(status().isBadRequest())
                .andExpect(content().string(not(containsString("secret-password"))));
    }

    @Test
    void businessAndUnexpectedExceptionsHaveSafeCommonResponses() throws Exception {
        mvc.perform(get("/api/contract/business").with(user()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("TIMETABLE_SLOT_OVERLAP"));
        mvc.perform(get("/api/contract/failure").with(user()))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.error.code").value("INTERNAL_ERROR"))
                .andExpect(content().string(not(containsString("secret-token"))));
    }

    @Test
    void corsPreflightAllowsConfiguredOriginAndAccountHeader() throws Exception {
        mvc.perform(options("/api/contract/user")
                        .header("Origin", "https://app.example.com")
                        .header("Access-Control-Request-Method", "GET")
                        .header("Access-Control-Request-Headers", "X-Bedrock-Account-Id"))
                .andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Allow-Origin", "https://app.example.com"))
                .andExpect(header().string("Access-Control-Allow-Credentials", "true"));
        mvc.perform(options("/api/contract/user")
                        .header("Origin", "https://untrusted.example.com")
                        .header("Access-Control-Request-Method", "GET"))
                .andExpect(status().isForbidden());
    }

    private RequestPostProcessor user() {
        var principal = new SessionPrincipal(USER_ID, UUID.randomUUID(), "WEB", false);
        return authentication(UsernamePasswordAuthenticationToken.authenticated(principal, null,
                List.of(new SimpleGrantedAuthority("ROLE_USER"))));
    }

    @RestController
    static class ContractController {
        @GetMapping("/api/contract/user")
        Map<String, UUID> user(@CurrentUser SessionPrincipal principal) {
            return Map.of("userId", principal.userId());
        }

        @PostMapping("/api/contract/write")
        Input write(@Valid @RequestBody Input input) {
            return input;
        }

        @GetMapping("/api/contract/business")
        void business() {
            throw new BusinessException(Be3ScheduleErrorCode.TIMETABLE_SLOT_OVERLAP);
        }

        @GetMapping("/api/contract/failure")
        void failure() {
            throw new IllegalStateException("secret-token");
        }
    }

    record Input(@NotBlank String name) {
    }
}
