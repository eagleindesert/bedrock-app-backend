package com.bedrock.app;

import com.bedrock.app.auth.domain.User;
import com.bedrock.app.collection.domain.Collection;
import com.bedrock.app.common.auth.SessionPrincipal;
import com.bedrock.app.common.domain.SwatchColor;
import com.bedrock.app.item.domain.Item;
import com.bedrock.app.support.PostgresIntegrationSupport;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.session.Session;
import org.springframework.session.SessionRepository;
import org.springframework.session.data.redis.RedisSessionRepository;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;

import java.time.Duration;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = "spring.docker.compose.enabled=false")
class AppApplicationTests extends PostgresIntegrationSupport {
    private static final GenericContainer<?> REDIS = new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
            .withExposedPorts(6379);

    @Autowired
    private SessionRepository<?> sessions;
    @PersistenceContext
    private EntityManager entities;

    @BeforeAll
    static void startRedis() {
        REDIS.start();
    }

    @AfterAll
    static void stopRedis() {
        REDIS.stop();
    }

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry properties) {
        properties.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        properties.add("spring.datasource.username", POSTGRES::getUsername);
        properties.add("spring.datasource.password", POSTGRES::getPassword);
        properties.add("spring.data.redis.host", REDIS::getHost);
        properties.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
    }

    @Test
    void redisPersistsPrincipalWithThirtyMinuteTimeout() {
        assertThat(sessions).isInstanceOf(RedisSessionRepository.class);
        assertSessionRoundTrip(sessions);
    }

    private <S extends Session> void assertSessionRoundTrip(SessionRepository<S> repository) {
        var principal = new SessionPrincipal(UUID.randomUUID(), UUID.randomUUID(), "WEB", false);
        S session = repository.createSession();
        session.setAttribute(SessionPrincipal.SESSION_ATTRIBUTE, principal);
        assertThat(session.getMaxInactiveInterval()).isEqualTo(Duration.ofMinutes(30));
        repository.save(session);
        S restored = repository.findById(session.getId());
        assertThat(restored).isNotNull();
        assertThat((Object) restored.getAttribute(SessionPrincipal.SESSION_ATTRIBUTE)).isEqualTo(principal);
        repository.deleteById(session.getId());
    }

    @Test
    @Transactional
    void canonicalEntitiesMatchDdlAndReceiveAuditTimesAndSyncSequences() {
        assertThat(entities.getMetamodel().getEntities()).hasSize(31);
        var user = User.builder().id(UUID.randomUUID()).email("model@example.com")
                .name("Model test").status("ACTIVE").build();
        entities.persist(user);
        var collection = Collection.builder().id(UUID.randomUUID()).userId(user.getId())
                .nameEnc("ciphertext-fixture").color(SwatchColor.BLUE).icon("folder").build();
        entities.persist(collection);
        var item = Item.builder().id(UUID.randomUUID()).userId(user.getId())
                .collectionId(collection.getId()).build();
        entities.persist(item);
        entities.flush();
        assertThat(user.getCreatedAt()).isNotNull();
        assertThat(item.getUpdatedAt()).isNotNull();
        assertThat(collection.getSyncSeq()).isPositive();
        assertThat(item.getSyncSeq()).isGreaterThan(collection.getSyncSeq());
    }
}
