package com.bedrock.app.mail.persistence;

import com.bedrock.app.mail.domain.MailCode;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface MailCodeRepository extends JpaRepository<MailCode, UUID> {
    Optional<MailCode> findFirstBySessionBindingIdAndConsumedAtIsNullOrderByCreatedAtDesc(String binding);

    @Modifying(flushAutomatically = true)
    @Query("update MailCode code set code.consumedAt = :now where code.sessionBindingId = :binding and code.consumedAt is null")
    void invalidateSession(String binding, Instant now);

    @Modifying(flushAutomatically = true)
    @Query("update MailCode code set code.consumedAt = :now where code.email = :email and code.purpose = 'SIGNUP' and code.consumedAt is null")
    void invalidatePendingSignup(String email, Instant now);

    @Modifying
    @Query("delete from MailCode code where code.expiresAt < :before")
    void deleteExpiredBefore(Instant before);
}
