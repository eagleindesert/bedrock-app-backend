package com.bedrock.app.common;

import com.bedrock.app.common.auth.SessionPrincipal;
import com.bedrock.app.common.config.PasswordConfig;
import com.bedrock.app.common.config.PortStubAutoConfiguration;
import com.bedrock.app.common.config.SessionConfig;
import com.bedrock.app.common.error.BusinessException;
import com.bedrock.app.common.port.*;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.session.web.http.CookieSerializer;

import java.io.*;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.mock;

class SharedConfigurationTest {
    @Test
    void argon2MeetsCostAndRejectsIncorrectPassword() {
        var encoder = new PasswordConfig().passwordEncoder(19456, 2, 1);
        String encoded = encoder.encode("correct-password");
        assertThat(encoded).startsWith("$argon2id$v=19$m=19456,t=2,p=1$");
        assertThat(encoder.matches("correct-password", encoded)).isTrue();
        assertThat(encoder.matches("wrong-password", encoded)).isFalse();
        assertThatThrownBy(() -> new PasswordConfig().passwordEncoder(1024, 2, 1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void sessionPrincipalSurvivesRedisJavaSerialization() throws Exception {
        var principal = new SessionPrincipal(UUID.randomUUID(), UUID.randomUUID(), "WEB", false);
        var bytes = new ByteArrayOutputStream();
        try (var output = new ObjectOutputStream(bytes)) {
            output.writeObject(principal);
        }
        try (var input = new ObjectInputStream(new ByteArrayInputStream(bytes.toByteArray()))) {
            assertThat(input.readObject()).isEqualTo(principal);
        }
    }

    @Test
    void sessionCookieIsSecureHttpOnlyLaxAndHostOnly() {
        var response = new MockHttpServletResponse();
        new SessionConfig().cookieSerializer(true).writeCookieValue(
                new CookieSerializer.CookieValue(new MockHttpServletRequest(), response, "session-id"));
        assertThat(response.getHeader("Set-Cookie"))
                .startsWith("BEDROCK_SESSION=").contains("Secure", "HttpOnly", "SameSite=Lax", "Path=/")
                .doesNotContain("Domain=", "Max-Age=");
    }

    @Test
    void missingPortsFailClosedAndRealProviderReplacesOnlyItsStub() {
        var runner = new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(PortStubAutoConfiguration.class));
        runner.run(context -> {
            assertThat(context).hasSingleBean(UserCryptoPort.class).hasSingleBean(ItemBatchWritePort.class)
                    .hasSingleBean(ConflictRegistryPort.class).hasSingleBean(ScheduleMutationPort.class)
                    .hasSingleBean(WorkspaceLifecyclePort.class);
            assertThatThrownBy(() -> context.getBean(UserCryptoPort.class).encrypt(UUID.randomUUID(), "secret"))
                    .isInstanceOf(BusinessException.class).hasMessageNotContaining("secret");
            assertThatThrownBy(() -> context.getBean(WorkspaceLifecyclePort.class)
                    .initializeWorkspaceForNewUser(UUID.randomUUID(), "Asia/Seoul")).isInstanceOf(BusinessException.class);
        });
        var implementation = mock(UserCryptoPort.class);
        runner.withBean(UserCryptoPort.class, () -> implementation).run(context ->
                assertThat(context.getBean(UserCryptoPort.class)).isSameAs(implementation));
    }
}
