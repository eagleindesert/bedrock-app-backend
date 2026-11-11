package com.bedrock.app.common.config;

import com.bedrock.app.common.error.BusinessException;
import com.bedrock.app.common.error.CommonErrorCode;
import com.bedrock.app.common.port.ConflictRegistryPort;
import com.bedrock.app.common.port.ItemBatchWritePort;
import com.bedrock.app.common.port.ScheduleMutationPort;
import com.bedrock.app.common.port.UserCryptoPort;
import com.bedrock.app.common.port.WorkspaceLifecyclePort;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;

import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;

/** 담당 구현체가 등록되면 자동으로 물러나는 초기 연결용 Stub. 성공이나 평문 암호화를 가장하지 않는다. */
@AutoConfiguration
public class PortStubAutoConfiguration {
    private static BusinessException unavailable() {
        return new BusinessException(CommonErrorCode.FEATURE_NOT_READY);
    }

    @Bean
    @ConditionalOnMissingBean(UserCryptoPort.class)
    public UserCryptoPort userCryptoPort() {
        return new UserCryptoPort() {
            @Override
            public void createDataKeyForUser(UUID userId) { throw unavailable(); }

            @Override
            public String encrypt(UUID userId, String plainText) { throw unavailable(); }

            @Override
            public String decrypt(UUID userId, String cipherText) { throw unavailable(); }

            @Override
            public void deleteDataKeyForUser(UUID userId) { throw unavailable(); }
        };
    }

    @Bean
    @ConditionalOnMissingBean(ItemBatchWritePort.class)
    public ItemBatchWritePort itemBatchWritePort() {
        return new ItemBatchWritePort() {
            @Override
            public BatchWriteResult executeAtomicBatch(UUID userId, BatchWriteCommand command) {
                throw unavailable();
            }

            @Override
            public BatchStatusResult getBatchStatus(UUID userId, UUID batchId) {
                throw unavailable();
            }
        };
    }

    @Bean
    @ConditionalOnMissingBean(ConflictRegistryPort.class)
    public ConflictRegistryPort conflictRegistryPort() {
        return (userId, occurrence, fieldKey, plainCandidateJson, sourceWriteId) -> { throw unavailable(); };
    }

    @Bean
    @ConditionalOnMissingBean(ScheduleMutationPort.class)
    public ScheduleMutationPort scheduleMutationPort() {
        return new ScheduleMutationPort() {
            @Override
            public void validateAndSaveTimetable(UUID userId, UUID itemId, UUID collectionId,
                    ItemBatchWritePort.TimetableSpec timetable) {
                throw unavailable();
            }

            @Override
            public void saveRecurrenceRule(UUID userId, UUID itemId, ItemBatchWritePort.RecurrenceSpec spec) {
                throw unavailable();
            }

            @Override
            public UUID applyOccurrenceException(UUID userId, ItemBatchWritePort.OccurrenceRef occurrence,
                    Map<String, Object> changedFields, boolean deleteOccurrence, UUID writeId) {
                throw unavailable();
            }

            @Override
            public UUID splitAndModifySeries(UUID userId, UUID masterItemId, LocalDate effectiveFrom,
                    Map<String, Object> changedFields, boolean isTimetable, UUID writeId) {
                throw unavailable();
            }
        };
    }

    @Bean
    @ConditionalOnMissingBean(WorkspaceLifecyclePort.class)
    public WorkspaceLifecyclePort workspaceLifecyclePort() {
        return new WorkspaceLifecyclePort() {
            @Override
            public UUID initializeWorkspaceForNewUser(UUID userId) { throw unavailable(); }

            @Override
            public void purgeAllWorkspaceDataForUser(UUID userId) { throw unavailable(); }
        };
    }
}
