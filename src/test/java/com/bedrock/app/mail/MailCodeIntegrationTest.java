package com.bedrock.app.mail;

import com.bedrock.app.common.error.Be1AuthAiErrorCode;
import com.bedrock.app.common.error.BusinessException;
import com.bedrock.app.mail.domain.MailCodePurpose;
import com.bedrock.app.mail.persistence.MailBounceRepository;
import com.bedrock.app.mail.persistence.MailCodeRepository;
import com.bedrock.app.mail.service.MailCodeService;
import com.bedrock.app.mail.service.MailDeliveryService;
import com.bedrock.app.mail.service.MailRateLimiter;
import com.bedrock.app.support.PostgresIntegrationSupport;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.when;

@SpringBootTest(properties = {
        "spring.docker.compose.enabled=false",
        "bedrock.mail.code-hmac-key=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="
})
class MailCodeIntegrationTest extends PostgresIntegrationSupport {
    private static final GenericContainer<?> REDIS = new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
            .withExposedPorts(6379);
    private static final Instant NOW = Instant.parse("2026-11-15T00:00:00Z");

    @Autowired private MailCodeService service;
    @Autowired private MailCodeRepository codes;
    @Autowired private MailBounceRepository bounces;
    @Autowired private PlatformTransactionManager manager;
    @MockitoBean private MailRateLimiter limits;
    @MockitoBean private MailDeliveryService delivery;
    @MockitoBean(name = "be1Clock") private Clock clock;

    private final AtomicReference<MailDeliveryService.Message> sent = new AtomicReference<>();

    @BeforeAll static void startRedis() { REDIS.start(); }
    @AfterAll static void stopRedis() { REDIS.stop(); }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry properties) {
        properties.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        properties.add("spring.datasource.username", POSTGRES::getUsername);
        properties.add("spring.datasource.password", POSTGRES::getPassword);
        properties.add("spring.data.redis.host", REDIS::getHost);
        properties.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
    }

    @BeforeEach
    void setup() {
        codes.deleteAll();
        bounces.deleteAll();
        sent.set(null);
        when(clock.instant()).thenReturn(NOW);
        when(limits.reserveRequest(anyString(), anyString(), anyString())).thenReturn(true);
        doAnswer(invocation -> { sent.set(invocation.getArgument(0)); return null; }).when(delivery).submit(any());
    }

    @Test
    void codeAndFailureCountSurviveCallerRollback() {
        var command = command("attempts@example.com", MailCodeService.DeliveryKind.CODE);
        service.start(command);
        UUID id = sent.get().challengeId();
        var callerTransaction = new TransactionTemplate(manager);
        String wrong = wrongCode(sent.get().code());
        assertThatThrownBy(() -> callerTransaction.execute(status -> service.verify(command.sessionBinding(), command.purpose(), wrong)))
                .isInstanceOf(BusinessException.class);
        assertThat(codes.findById(id).orElseThrow().getAttemptCount()).isEqualTo(1);
        assertThat(bounces.findById(command.email()).orElseThrow().getConsecutiveFailCount()).isEqualTo(1);
        assertThat(service.verify(command.sessionBinding(), command.purpose(), sent.get().code()).email()).isEqualTo(command.email());
        invalidCode(command, sent.get().code());
    }

    @Test
    void newSignupInvalidatesPreviousSessionAndNewWorkInvalidatesCurrentSession() {
        var first = command("same@example.com", MailCodeService.DeliveryKind.CODE);
        service.start(first);
        String firstCode = sent.get().code();
        var second = command(first.email(), MailCodeService.DeliveryKind.CODE);
        service.start(second);
        invalidCode(first, firstCode);
        String secondCode = sent.get().code();
        var reset = new MailCodeService.IssueCommand("another@example.com", second.sessionBinding(), "127.0.0.1",
                "IOS", MailCodePurpose.PASSWORD_RESET, MailCodeService.DeliveryKind.CODE);
        service.start(reset);
        invalidCode(second, secondCode);
        assertThat(service.verify(reset.sessionBinding(), reset.purpose(), sent.get().code()).email()).isEqualTo(reset.email());
    }

    @Test
    void resendInvalidatesOldCodeAndExpiryNeverExtendsFromVerification() {
        var command = command("resend@example.com", MailCodeService.DeliveryKind.CODE);
        service.start(command);
        UUID firstId = sent.get().challengeId();
        assertThatThrownBy(() -> service.resend(command)).isInstanceOfSatisfying(BusinessException.class,
                error -> assertThat(error.getErrorCode()).isEqualTo(Be1AuthAiErrorCode.MAIL_CODE_RATE_LIMITED));
        when(clock.instant()).thenReturn(NOW.plusSeconds(60));
        var next = service.resend(command);
        assertThat(codes.findById(firstId).orElseThrow().getConsumedAt()).isNotNull();
        assertThat(next.expiresAt()).isEqualTo(NOW.plusSeconds(660));
        when(clock.instant()).thenReturn(NOW.plusSeconds(660));
        invalidCode(command, sent.get().code());
    }

    @Test
    void fiveWrongAnswersInvalidateOneCodeAndOneHundredBlockTheAddress() {
        String email = "lock@example.com";
        for (int challenge = 0; challenge < 20; challenge++) {
            var command = command(email, MailCodeService.DeliveryKind.CODE);
            service.start(command);
            String correct = sent.get().code();
            for (int attempt = 0; attempt < 5; attempt++) invalidCode(command, wrongCode(correct));
            invalidCode(command, correct);
        }
        assertThat(bounces.findById(email).orElseThrow().getBlockedUntil()).isEqualTo(NOW.plusSeconds(86_400));
        sent.set(null);
        service.start(command(email, MailCodeService.DeliveryKind.CODE));
        assertThat(sent.get()).isNull();
    }

    @Test
    void existingAccountNoticeAndSilentRequestsCannotAuthenticate() {
        var existing = command("existing@example.com", MailCodeService.DeliveryKind.ALREADY_REGISTERED);
        var existingResponse = service.start(existing);
        invalidCode(existing, sent.get().code());
        sent.set(null);
        var missing = command("missing@example.com", MailCodeService.DeliveryKind.NONE);
        var missingResponse = service.start(missing);
        assertThat(existingResponse).isEqualTo(missingResponse);
        assertThat(sent.get()).isNull();
        invalidCode(missing, "000000");
    }

    private void invalidCode(MailCodeService.IssueCommand command, String code) {
        assertThatThrownBy(() -> service.verify(command.sessionBinding(), command.purpose(), code))
                .isInstanceOfSatisfying(BusinessException.class,
                        error -> assertThat(error.getErrorCode()).isEqualTo(Be1AuthAiErrorCode.INVALID_MAIL_CODE));
    }

    private MailCodeService.IssueCommand command(String email, MailCodeService.DeliveryKind kind) {
        return new MailCodeService.IssueCommand(email, UUID.randomUUID().toString(), "127.0.0.1", "IOS", MailCodePurpose.SIGNUP, kind);
    }

    private String wrongCode(String correct) {
        return correct.equals("000000") ? "000001" : "000000";
    }
}
