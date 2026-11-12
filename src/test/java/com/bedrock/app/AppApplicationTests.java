package com.bedrock.app;

import com.bedrock.app.auth.domain.User;
import com.bedrock.app.collection.domain.Collection;
import com.bedrock.app.common.auth.SessionPrincipal;
import com.bedrock.app.common.domain.SwatchColor;
import com.bedrock.app.item.domain.Item;
import com.bedrock.app.sync.domain.Workspace;
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
import java.time.Instant;
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
        assertThat(entities.getMetamodel().getEntities()).hasSize(32);
        var user = User.builder().id(UUID.randomUUID()).email("model@example.com")
                .name("Model test").status("ACTIVE").build();
        entities.persist(user);
        var workspace = Workspace.builder().id(UUID.randomUUID()).ownerUserId(user.getId()).build();
        entities.persist(workspace);
        var collection = Collection.builder().id(UUID.randomUUID()).userId(user.getId()).workspaceId(workspace.getId())
                .nameEnc("ciphertext-fixture").color(SwatchColor.BLUE).icon("folder").build();
        entities.persist(collection);
        var item = Item.builder().id(UUID.randomUUID()).userId(user.getId()).workspaceId(workspace.getId())
                .collectionId(collection.getId()).build();
        entities.persist(item);
        entities.flush();
        assertThat(user.getCreatedAt()).isNotNull();
        assertThat(item.getUpdatedAt()).isNotNull();
        assertThat(collection.getSyncSeq()).isPositive();
        assertThat(item.getSyncSeq()).isGreaterThan(collection.getSyncSeq());
    }

    @Test
    @Transactional
    void instantsAreStoredAtSecondPrecision() {
        var user = User.builder().id(UUID.randomUUID()).email("seconds@example.com")
                .name("Seconds test").status("ACTIVE").build();
        entities.persist(user);
        var workspace = Workspace.builder().id(UUID.randomUUID()).ownerUserId(user.getId()).build();
        entities.persist(workspace);
        var collection = Collection.builder().id(UUID.randomUUID()).userId(user.getId()).workspaceId(workspace.getId())
                .nameEnc("ciphertext-fixture").color(SwatchColor.BLUE).icon("folder").build();
        entities.persist(collection);
        var item = Item.builder().id(UUID.randomUUID()).userId(user.getId()).workspaceId(workspace.getId()).collectionId(collection.getId())
                .hasSchedule(true).startAt(Instant.parse("2026-11-12T08:30:15.987Z")).build();
        entities.persist(item);
        entities.flush();
        entities.clear();

        var fractions = entities.createNativeQuery("SELECT EXTRACT(MICROSECONDS FROM start_at)::bigint % 1000000, "
                        + "EXTRACT(MICROSECONDS FROM created_at)::bigint % 1000000 FROM items WHERE id = ?1")
                .setParameter(1, item.getId()).getSingleResult();
        assertThat((Object[]) fractions).containsExactly(0L, 0L);
        assertThat(entities.find(Item.class, item.getId()).getStartAt()).isEqualTo(Instant.parse("2026-11-12T08:30:15Z"));
    }

    @Test
    @Transactional
    void itemKeepsDeviceCreatedAtAndJpaRereadsTriggerSyncSeq() {
        var user = User.builder().id(UUID.randomUUID()).email("device-time@example.com")
                .name("Device time").status("ACTIVE").build();
        entities.persist(user);
        var workspace = Workspace.builder().id(UUID.randomUUID()).ownerUserId(user.getId()).build();
        entities.persist(workspace);
        var collection = Collection.builder().id(UUID.randomUUID()).userId(user.getId()).workspaceId(workspace.getId())
                .nameEnc("ciphertext-fixture").color(SwatchColor.BLUE).icon("folder").build();
        entities.persist(collection);
        var offline = Item.builder().id(UUID.randomUUID()).userId(user.getId()).workspaceId(workspace.getId())
                .collectionId(collection.getId()).createdAt(Instant.parse("2026-11-01T09:00:00Z")).build();
        var future = Item.builder().id(UUID.randomUUID()).userId(user.getId()).workspaceId(workspace.getId())
                .collectionId(collection.getId()).createdAt(Instant.parse("2999-01-01T00:00:00Z")).build();
        entities.persist(offline);
        entities.persist(future);
        entities.flush();

        assertThat(offline.getCreatedAt()).isEqualTo(Instant.parse("2026-11-01T09:00:00Z"));
        assertThat(offline.getServerCreatedAt()).isAfter(offline.getCreatedAt());
        assertThat(future.getCreatedAt()).isEqualTo(future.getServerCreatedAt());

        // 번호는 트리거가 정하고(sync_seq를 직접 넣어도 무시된다), 엔티티는 새 번호를 다시 읽는다.
        long inserted = collection.getSyncSeq();
        entities.createNativeQuery("UPDATE collections SET icon = 'star', sync_seq = 1 WHERE id = ?1")
                .setParameter(1, collection.getId()).executeUpdate();
        entities.refresh(collection);
        assertThat(collection.getSyncSeq()).isGreaterThan(inserted);
    }
}
