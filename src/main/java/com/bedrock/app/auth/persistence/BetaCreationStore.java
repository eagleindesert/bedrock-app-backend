package com.bedrock.app.auth.persistence;

import com.bedrock.app.common.error.Be1AuthAiErrorCode;
import com.bedrock.app.common.error.BusinessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;

/** 사용자·쓰기의 HMAC과 생성 개수만 보관한다. 아이템 식별자·필드·본문을 받지 않는다. */
@Repository("betaCreationStore")
public class BetaCreationStore {
    private final JdbcTemplate jdbc;
    public BetaCreationStore(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public void record(String subjectHmac, String writeHmac, int createdCount, Instant now) {
        int inserted = jdbc.update("INSERT INTO auth_beta_creations (subject_hmac, write_hmac, created_count, observed_at) VALUES (?, ?, ?, ?) ON CONFLICT DO NOTHING",
                subjectHmac, writeHmac, createdCount, Timestamp.from(now));
        if (inserted == 1) return;
        Integer previous = jdbc.queryForObject("SELECT created_count FROM auth_beta_creations WHERE subject_hmac = ? AND write_hmac = ?",
                Integer.class, subjectHmac, writeHmac);
        if (!Integer.valueOf(createdCount).equals(previous)) throw new BusinessException(Be1AuthAiErrorCode.RESOURCE_VERSION_CHANGED);
    }

    public long createdOn(LocalDate day) {
        Long count = jdbc.queryForObject("SELECT COALESCE(sum(created_count), 0) FROM auth_beta_creations WHERE observed_at >= ? AND observed_at < ?",
                Long.class, Timestamp.from(day.atStartOfDay().toInstant(ZoneOffset.UTC)), Timestamp.from(day.plusDays(1).atStartOfDay().toInstant(ZoneOffset.UTC)));
        return count == null ? 0 : count;
    }

    public boolean hasEvents() {
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS (SELECT 1 FROM auth_beta_creations)", Boolean.class));
    }

    public void erase(String subjectHmac) { jdbc.update("DELETE FROM auth_beta_creations WHERE subject_hmac = ?", subjectHmac); }
    public void expireBefore(Instant before) { jdbc.update("DELETE FROM auth_beta_creations WHERE observed_at < ?", Timestamp.from(before)); }
}
