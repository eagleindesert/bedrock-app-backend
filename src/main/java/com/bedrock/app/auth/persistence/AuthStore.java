package com.bedrock.app.auth.persistence;

import com.bedrock.app.auth.dto.AuthDtos;
import com.bedrock.app.common.error.Be1AuthAiErrorCode;
import com.bedrock.app.common.error.BusinessException;
import com.bedrock.app.common.error.CommonErrorCode;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

/** BE1 소유 테이블에만 접근한다. 다른 담당의 데이터 수명은 WorkspaceLifecyclePort가 맡는다. */
@Repository("be1AuthStore")
public class AuthStore {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;
    private final TransactionTemplate independent;

    public AuthStore(JdbcTemplate jdbc, PlatformTransactionManager manager) {
        this.jdbc = jdbc;
        this.transaction = new TransactionTemplate(manager);
        this.independent = new TransactionTemplate(manager);
        this.independent.setPropagationBehavior(org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    public void planKeyCleanup(UUID userId) {
        independent.executeWithoutResult(status -> jdbc.update("INSERT INTO auth_key_cleanup (user_id) VALUES (?) ON CONFLICT DO NOTHING", userId));
    }

    public <T> T keyCreationLock(UUID userId, Supplier<T> work) {
        return transaction.execute(status -> {
            jdbc.queryForList("SELECT pg_advisory_xact_lock(hashtextextended(?, 0))", "bedrock:key-create:" + userId);
            return work.get();
        });
    }

    public void finishKeyCleanup(UUID userId) { jdbc.update("DELETE FROM auth_key_cleanup WHERE user_id = ?", userId); }
    public List<UUID> staleKeyCreations(Instant before) {
        return jdbc.queryForList("SELECT user_id FROM auth_key_cleanup WHERE created_at < ? ORDER BY created_at LIMIT 100", UUID.class, timestamp(before));
    }

    public <T> T locked(String email, Supplier<T> work) {
        return transaction.execute(status -> {
            jdbc.queryForList("SELECT pg_advisory_xact_lock(hashtextextended(?, 0))", "bedrock:auth:" + email);
            return work.get();
        });
    }

    public <T> T locked(UUID userId, Supplier<T> work) {
        var user = user(userId).orElseThrow(() -> new BusinessException(Be1AuthAiErrorCode.RESOURCE_NOT_FOUND));
        return locked(user.email(), () -> {
            if (user(userId).isEmpty()) throw new BusinessException(Be1AuthAiErrorCode.RESOURCE_NOT_FOUND);
            return work.get();
        });
    }

    /** V9의 잠금 순서: 워크스페이스 → 계정 → AI 요청. 가입 포트의 반환값만 사용한다. */
    public <T> T workspaceLocked(UUID userId, Supplier<T> work) {
        return transaction.execute(status -> {
            UUID workspaceId = jdbc.query("SELECT home_workspace_id FROM users WHERE id = ?",
                    (row, index) -> row.getObject("home_workspace_id", UUID.class), userId).stream()
                    .filter(java.util.Objects::nonNull).findFirst().orElseThrow(() -> new BusinessException(CommonErrorCode.FEATURE_NOT_READY));
            jdbc.queryForList("SELECT pg_advisory_xact_lock(hashtext(?))", workspaceId.toString());
            return locked(userId, work);
        });
    }

    public void bindWorkspace(UUID userId, UUID workspaceId) {
        if (workspaceId == null) throw new BusinessException(CommonErrorCode.FEATURE_NOT_READY);
        int changed = jdbc.update("UPDATE users SET home_workspace_id = ? WHERE id = ? AND (home_workspace_id IS NULL OR home_workspace_id = ?)",
                workspaceId, userId, workspaceId);
        if (changed != 1) throw new BusinessException(Be1AuthAiErrorCode.RESOURCE_VERSION_CHANGED);
    }

    public Optional<Account> email(String email) {
        return jdbc.query("SELECT * FROM users WHERE email = ?", this::account, email).stream().findFirst();
    }

    public Optional<Account> user(UUID id) {
        return jdbc.query("SELECT * FROM users WHERE id = ?", this::account, id).stream().findFirst();
    }

    public long registeredUserCount() {
        Long count = jdbc.queryForObject("SELECT count(*) FROM users WHERE status IN ('ACTIVE', 'DELETION_PENDING')", Long.class);
        return count == null ? 0 : count;
    }

    public Account create(UUID id, String email, String name, String hash) {
        jdbc.update("INSERT INTO users (id, email, name, password_hash, status, created_at, updated_at) VALUES (?, ?, ?, ?, 'ACTIVE', date_trunc('second', now()), date_trunc('second', now()))", id, email, name, hash);
        return user(id).orElseThrow();
    }

    public void loginFailures(String email, int count, Instant lockedUntil) {
        jdbc.update("UPDATE users SET failed_login_count = ?, locked_until = ?, updated_at = date_trunc('second', now()) WHERE email = ?",
                count, timestamp(lockedUntil), email);
    }

    public void password(UUID userId, String hash) {
        jdbc.update("UPDATE users SET password_hash = ?, failed_login_count = 0, locked_until = NULL, updated_at = date_trunc('second', now()) WHERE id = ?", hash, userId);
    }

    public AuthDtos.UserDto rename(UUID userId, String name) {
        int changed = jdbc.update("UPDATE users SET name = ?, updated_at = date_trunc('second', now()) WHERE id = ? AND status = 'ACTIVE'", name, userId);
        if (changed != 1) throw new BusinessException(Be1AuthAiErrorCode.UNAUTHORIZED);
        return user(userId).orElseThrow().dto();
    }

    public void markDeleted(UUID userId, Instant now) {
        jdbc.update("UPDATE users SET status = 'DELETION_PENDING', deleted_at = ?, purge_after = ?, updated_at = ? WHERE id = ?",
                timestamp(now), timestamp(now.plusSeconds(30L * 86400)), timestamp(now), userId);
    }

    public void restore(UUID userId) {
        jdbc.update("UPDATE users SET status = 'ACTIVE', deleted_at = NULL, purge_after = NULL, failed_login_count = 0, locked_until = NULL, updated_at = date_trunc('second', now()) WHERE id = ?", userId);
    }

    public void delete(UUID userId) { jdbc.update("DELETE FROM users WHERE id = ?", userId); }

    public void markPurging(UUID userId) {
        jdbc.update("UPDATE users SET status = 'PURGING', updated_at = date_trunc('second', now()) WHERE id = ?", userId);
    }

    public List<UUID> purgeCandidates(Instant now) {
        return jdbc.queryForList("SELECT id FROM users WHERE (status = 'DELETION_PENDING' AND purge_after <= ?) OR status = 'PURGING' ORDER BY purge_after LIMIT 100", UUID.class, timestamp(now));
    }

    public List<UUID> pendingAppleRevocations() {
        return jdbc.queryForList("SELECT DISTINCT u.id FROM users u JOIN user_oauth_accounts a ON a.user_id = u.id WHERE u.status IN ('DELETION_PENDING', 'PURGING') AND a.apple_refresh_token_enc IS NOT NULL LIMIT 100", UUID.class);
    }

    public boolean knownDevice(UUID userId, String deviceHash) {
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS (SELECT 1 FROM user_devices WHERE user_id = ? AND device_id_hash = ?)", Boolean.class, userId, deviceHash));
    }

    public void registerDevice(UUID id, UUID userId, String deviceHash, String sessionId, AuthDtos.DeviceContext device,
            boolean remember, Instant now) {
        jdbc.update("INSERT INTO user_devices (id, user_id, device_id_hash, session_id, platform, model_name, remember_me, last_active_at, created_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, date_trunc('second', now()))",
                id, userId, deviceHash, sessionId, device.platform().name(), device.modelName(), remember, timestamp(now));
    }

    public Optional<Device> device(UUID userId, UUID deviceId) {
        return jdbc.query("SELECT * FROM user_devices WHERE user_id = ? AND id = ?", this::device, userId, deviceId).stream().findFirst();
    }

    public List<Device> devices(UUID userId) {
        return jdbc.query("SELECT * FROM user_devices WHERE user_id = ? AND revoked_at IS NULL ORDER BY last_active_at DESC, id", this::device, userId);
    }

    public void touchDevice(UUID userId, UUID deviceId, Instant now) {
        jdbc.update("UPDATE user_devices SET last_active_at = ? WHERE user_id = ? AND id = ? AND revoked_at IS NULL", timestamp(now), userId, deviceId);
    }

    public void revokeDevice(UUID userId, UUID deviceId, Instant now) {
        jdbc.update("UPDATE user_devices SET revoked_at = ? WHERE user_id = ? AND id = ? AND revoked_at IS NULL", timestamp(now), userId, deviceId);
    }

    public List<OAuthAccount> oauthAccounts(UUID userId) {
        return jdbc.query("SELECT * FROM user_oauth_accounts WHERE user_id = ?", this::oauthAccount, userId);
    }

    public Optional<OAuthAccount> oauth(AuthDtos.Provider provider, String subject) {
        return jdbc.query("SELECT * FROM user_oauth_accounts WHERE provider = ? AND provider_subject = ?", this::oauthAccount,
                provider.name(), subject).stream().findFirst();
    }

    public UUID linkOAuth(UUID userId, AuthDtos.Provider provider, String subject, String email, String refreshTokenEnc) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO user_oauth_accounts (id, user_id, provider, provider_subject, provider_email, apple_refresh_token_enc, created_at) VALUES (?, ?, ?, ?, ?, ?, date_trunc('second', now()))",
                id, userId, provider.name(), subject, email, refreshTokenEnc);
        return id;
    }

    public void unlinkOAuth(UUID userId, UUID id) { jdbc.update("DELETE FROM user_oauth_accounts WHERE user_id = ? AND id = ?", userId, id); }

    public void refreshToken(UUID userId, UUID id, String encrypted) {
        jdbc.update("UPDATE user_oauth_accounts SET apple_refresh_token_enc = ? WHERE user_id = ? AND id = ?", encrypted, userId, id);
    }

    public void saveNotMe(UUID userId, UUID oauthId, String hmac, Instant expiresAt) {
        jdbc.update("INSERT INTO not_me_tokens (id, user_id, oauth_account_id, token_hmac, expires_at, created_at) VALUES (?, ?, ?, ?, ?, date_trunc('second', now()))",
                UUID.randomUUID(), userId, oauthId, hmac, timestamp(expiresAt));
    }

    public Optional<NotMe> notMe(String hmac, Instant now) {
        return jdbc.query("SELECT n.*, a.provider FROM not_me_tokens n JOIN user_oauth_accounts a ON a.id = n.oauth_account_id WHERE n.token_hmac = ? AND n.used_at IS NULL AND n.expires_at > ?",
                (row, index) -> new NotMe(row.getObject("id", UUID.class), row.getObject("user_id", UUID.class),
                        row.getObject("oauth_account_id", UUID.class), AuthDtos.Provider.valueOf(row.getString("provider")), instant(row, "expires_at")),
                hmac, timestamp(now)).stream().findFirst();
    }

    public boolean consumeNotMe(UUID id, Instant now) {
        return jdbc.update("UPDATE not_me_tokens SET used_at = ? WHERE id = ? AND used_at IS NULL AND expires_at > ?", timestamp(now), id, timestamp(now)) == 1;
    }

    private Account account(ResultSet row, int index) throws SQLException {
        return new Account(row.getObject("id", UUID.class), row.getString("email"), row.getString("name"),
                row.getString("password_hash"), row.getString("status"), row.getInt("failed_login_count"), instant(row, "locked_until"),
                instant(row, "created_at"), instant(row, "purge_after"));
    }

    private Device device(ResultSet row, int index) throws SQLException {
        return new Device(row.getObject("id", UUID.class), row.getString("session_id"), AuthDtos.Platform.valueOf(row.getString("platform")),
                row.getString("model_name"), row.getBoolean("remember_me"), instant(row, "last_active_at"), instant(row, "revoked_at"));
    }

    private OAuthAccount oauthAccount(ResultSet row, int index) throws SQLException {
        return new OAuthAccount(row.getObject("id", UUID.class), row.getObject("user_id", UUID.class),
                AuthDtos.Provider.valueOf(row.getString("provider")), row.getString("provider_subject"),
                row.getString("provider_email"), row.getString("apple_refresh_token_enc"));
    }

    private static Timestamp timestamp(Instant instant) { return instant == null ? null : Timestamp.from(instant); }
    private static Instant instant(ResultSet row, String column) throws SQLException {
        Timestamp value = row.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }

    public record Account(UUID id, String email, String name, String passwordHash, String status, int failedLogins,
            Instant lockedUntil, Instant createdAt, Instant purgeAfter) {
        public AuthDtos.UserDto dto() { return new AuthDtos.UserDto(id, email, name, createdAt); }
        public boolean active() { return "ACTIVE".equals(status); }
        public boolean deletionPending() { return "DELETION_PENDING".equals(status); }
        @Override public String toString() { return "Account[id=" + id + "]"; }
    }
    public record Device(UUID id, String sessionId, AuthDtos.Platform platform, String modelName, boolean rememberMe,
            Instant lastActiveAt, Instant revokedAt) {
        @Override public String toString() { return "Device[id=" + id + "]"; }
    }
    public record OAuthAccount(UUID id, UUID userId, AuthDtos.Provider provider, String subject, String email, String refreshTokenEnc) {
        @Override public String toString() { return "OAuthAccount[id=" + id + "]"; }
    }
    public record NotMe(UUID id, UUID userId, UUID oauthId, AuthDtos.Provider provider, Instant expiresAt) {}
}
