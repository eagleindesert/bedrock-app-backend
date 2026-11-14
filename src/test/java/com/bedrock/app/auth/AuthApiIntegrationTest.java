package com.bedrock.app.auth;

import com.bedrock.app.auth.security.CompromisedPasswordLookup;
import com.bedrock.app.auth.security.PasswordService;
import com.bedrock.app.auth.service.AuthNotifications;
import com.bedrock.app.auth.service.AuthWorkGuard;
import com.bedrock.app.common.port.UserCryptoPort;
import com.bedrock.app.common.port.WorkspaceLifecyclePort;
import com.bedrock.app.mail.service.MailDeliveryService;
import com.bedrock.app.mail.service.MailRateLimiter;
import com.bedrock.app.support.PostgresIntegrationSupport;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.Clock;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

@SpringBootTest(properties = {"spring.docker.compose.enabled=false", "server.servlet.session.cookie.secure=false",
        "bedrock.mail.code-hmac-key=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="})
@AutoConfigureMockMvc
class AuthApiIntegrationTest extends PostgresIntegrationSupport {
    private static final GenericContainer<?> REDIS = new GenericContainer<>(DockerImageName.parse("redis:7-alpine")).withExposedPorts(6379);
    private static final Instant NOW = Instant.parse("2026-11-15T00:00:00Z");
    private static final String PASSWORD = "test-account-password-2026";
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired JdbcTemplate jdbc;
    @Autowired AuthWorkGuard workGuard;
    @MockitoBean CompromisedPasswordLookup compromised;
    @MockitoBean UserCryptoPort crypto;
    @MockitoBean WorkspaceLifecyclePort workspace;
    @MockitoBean MailRateLimiter limits;
    @MockitoBean MailDeliveryService delivery;
    @MockitoBean AuthNotifications notifications;
    @MockitoBean(name = "be1Clock") Clock clock;
    @MockitoSpyBean PasswordService passwords;
    private final AtomicReference<MailDeliveryService.Message> sent = new AtomicReference<>();

    @BeforeAll static void startRedis() { REDIS.start(); }
    @AfterAll static void stopRedis() { REDIS.stop(); }
    @DynamicPropertySource static void properties(DynamicPropertyRegistry properties) {
        properties.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        properties.add("spring.datasource.username", POSTGRES::getUsername);
        properties.add("spring.datasource.password", POSTGRES::getPassword);
        properties.add("spring.data.redis.host", REDIS::getHost);
        properties.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
    }
    @BeforeEach void setup() {
        jdbc.update("DELETE FROM users"); jdbc.update("DELETE FROM mail_codes"); jdbc.update("DELETE FROM mail_bounces");
        when(clock.instant()).thenReturn(NOW);
        when(limits.reserveRequest(anyString(), anyString(), anyString())).thenReturn(true);
        when(workspace.initializeWorkspaceForNewUser(any(), anyString())).thenReturn(UUID.randomUUID());
        doAnswer(invocation -> { sent.set(invocation.getArgument(0)); return null; }).when(delivery).submit(any());
        sent.set(null); clearInvocations(passwords);
    }

    @Test void checksCodeBeforePasswordAndRotatesSession() throws Exception {
        var client = new Client(); client.csrf();
        String before = client.cookies.get("BEDROCK_SESSION").getValue();
        assertThat(client.write(post("/api/v1/auth/signup"), signup("first@example.com")).getResponse().getStatus()).isEqualTo(202);
        String correct = sent.get().code();
        String wrong = "000000".equals(correct) ? "111111" : "000000";
        var bad = client.write(post("/api/v1/auth/mail-codes/complete"), Map.of("code", wrong, "password", "short"));
        assertThat(json(bad).path("error").path("code").asText()).isEqualTo("INVALID_MAIL_CODE");
        verify(passwords, never()).encodeVerifiedPassword(any());
        var good = client.write(post("/api/v1/auth/mail-codes/complete"), Map.of("code", correct, "password", PASSWORD));
        assertThat(good.getResponse().getStatus()).isEqualTo(200);
        assertThat(json(good).path("data").path("nextStep").asText()).isEqualTo("AUTHENTICATED");
        assertThat(client.cookies.get("BEDROCK_SESSION").getValue()).isNotEqualTo(before);
        assertThat(json(good).toString()).doesNotContain("sessionId", PASSWORD);
        verify(workspace).initializeWorkspaceForNewUser(any(), eq("Asia/Seoul"));
    }

    @Test void oauthNonceCannotBeConsumedTwiceBeforeSessionSave() {
        UUID challenge = UUID.randomUUID();
        workGuard.consumeOAuthChallenge(challenge);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> workGuard.consumeOAuthChallenge(challenge))
                .isInstanceOf(com.bedrock.app.common.error.BusinessException.class);
    }

    @Test void passwordResetRevokesExistingDevicesAndDoesNotSignIn() throws Exception {
        var signed = createAccount("reset@example.com");
        var reset = new Client();
        reset.write(post("/api/v1/auth/password-reset"), Map.of("email", "reset@example.com", "device", device()));
        String code = sent.get().code();
        var result = reset.write(post("/api/v1/auth/mail-codes/complete"), Map.of("code", code, "password", "new-account-password-2026"));
        assertThat(json(result).path("data").path("nextStep").asText()).isEqualTo("PASSWORD_RESET_COMPLETED");
        assertThat(json(reset.read("/api/v1/auth/session")).path("data").path("authenticated").asBoolean()).isFalse();
        var old = json(signed.read("/api/v1/auth/session")).path("data");
        assertThat(old.path("authenticated").asBoolean()).isFalse();
        assertThat(old.path("sessionEndReason").asText()).isEqualTo("PASSWORD_RESET");
    }

    @Test void backgroundSessionChecksDoNotExtendOrdinaryWebIdleTime() throws Exception {
        var client = createAccount("idle@example.com");
        when(clock.instant()).thenReturn(NOW.plusSeconds(1799));
        assertThat(json(client.read("/api/v1/auth/session")).path("data").path("authenticated").asBoolean()).isTrue();
        when(clock.instant()).thenReturn(NOW.plusSeconds(1800));
        var ended = json(client.read("/api/v1/auth/session")).path("data");
        assertThat(ended.path("authenticated").asBoolean()).isFalse();
        assertThat(ended.path("sessionEndReason").asText()).isEqualTo("EXPIRED");
    }

    @Test void deletionRequiresMailProofBeforeRestoration() throws Exception {
        var client = createAccount("restore@example.com");
        var deletion = client.write(delete("/api/v1/users/me"), null);
        assertThat(json(deletion).path("data").path("purgeAfter").asText()).isEqualTo(NOW.plusSeconds(30L * 86400).toString());
        var recovery = new Client();
        var login = recovery.write(post("/api/v1/auth/login"), Map.of("email", "restore@example.com", "password", PASSWORD, "device", device()));
        assertThat(json(login).path("data").path("nextStep").asText()).isEqualTo("MAIL_CODE_REQUIRED");
        var premature = recovery.write(post("/api/v1/auth/account-recovery"), Map.of("action", "RESTORE", "confirmed", true));
        assertThat(premature.getResponse().getStatus()).isEqualTo(400);
        var verified = recovery.write(post("/api/v1/auth/mail-codes/complete"), Map.of("code", sent.get().code()));
        assertThat(json(verified).path("data").path("nextStep").asText()).isEqualTo("RECOVERY_CHOICE_REQUIRED");
        var restored = recovery.write(post("/api/v1/auth/account-recovery"), Map.of("action", "RESTORE", "confirmed", true));
        assertThat(json(restored).path("data").path("nextStep").asText()).isEqualTo("AUTHENTICATED");
    }

    private Client createAccount(String address) throws Exception {
        var client = new Client(); client.write(post("/api/v1/auth/signup"), signup(address));
        var response = client.write(post("/api/v1/auth/mail-codes/complete"), Map.of("code", sent.get().code(), "password", PASSWORD));
        assertThat(response.getResponse().getStatus()).isEqualTo(200); return client;
    }
    private Map<String, Object> signup(String email) { return Map.of("email", email, "name", "테스트", "device", device()); }
    private Map<String, Object> device() { return Map.of("platform", "WEB", "timeZone", "Asia/Seoul", "modelName", "test"); }
    private JsonNode json(MvcResult result) throws java.io.UnsupportedEncodingException { return mapper.readTree(result.getResponse().getContentAsString()); }

    private final class Client {
        private final Map<String, Cookie> cookies = new HashMap<>();
        private String accountId;
        private JsonNode csrf() throws Exception { return json(read("/api/v1/auth/csrf")).path("data"); }
        private MvcResult read(String path) throws Exception { return perform(get(path)); }
        private MvcResult write(MockHttpServletRequestBuilder builder, Object body) throws Exception {
            var token = csrf(); builder.header(token.path("headerName").asText(), token.path("token").asText());
            if (body != null) builder.contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsBytes(body));
            return perform(builder);
        }
        private MvcResult perform(MockHttpServletRequestBuilder builder) throws Exception {
            if (!cookies.isEmpty()) builder.cookie(cookies.values().toArray(Cookie[]::new));
            if (accountId != null) builder.header("X-Bedrock-Account-Id", accountId);
            var result = mvc.perform(builder).andReturn();
            for (var cookie : result.getResponse().getCookies()) {
                if (cookie.getMaxAge() == 0) cookies.remove(cookie.getName()); else cookies.put(cookie.getName(), cookie);
            }
            if (result.getResponse().getContentType() != null && result.getResponse().getContentType().contains("json")) {
                var data = json(result).path("data");
                if (data.path("session").path("authenticated").asBoolean()) accountId = data.path("session").path("user").path("id").asText();
            }
            return result;
        }
    }
}
