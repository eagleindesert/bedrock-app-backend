package com.bedrock.app.common.port;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 명세 F-43 · F-18 · F-31 · F-34 · F-42 · F-49:
 * 만들기 · 고치기 · 지우기 · 회차 예외 · 반복 나누기를 한 묶음(전부 아니면 없음)으로 실행하고,
 * batchId로 재전송 중복 적용을 막는다.
 */
public interface ItemBatchWritePort {

    BatchWriteResult executeAtomicBatch(UUID userId, BatchWriteCommand command);

    BatchStatusResult getBatchStatus(UUID userId, UUID batchId);

    record BatchWriteCommand(
            UUID batchId,
            String batchType, // AI_MUTATION | TIMETABLE_CREATE | TODO_POSTPONE | ICS_IMPORT | BULK_DELETE
            List<ItemCreateSpec> creates,
            List<ItemPatchSpec> patches,
            List<ItemDeleteSpec> deletes,
            List<LinkSpec> linksToUpsert
    ) {}

    record ItemCreateSpec(
            UUID itemId,
            UUID collectionId,
            Map<String, Object> fields,
            RecurrenceSpec recurrence,
            List<TimetableSlotSpec> timetableSlots
    ) {}

    record ItemPatchSpec(
            UUID itemId,
            String scope, // SINGLE | THIS_OCCURRENCE | THIS_AND_FUTURE | ALL
            LocalDate effectiveDate,
            Map<String, Object> changedFields,
            Instant clientBaseTimestamp
    ) {}

    record ItemDeleteSpec(
            UUID itemId,
            String scope, // SINGLE | THIS_OCCURRENCE | THIS_AND_FUTURE | ALL
            LocalDate effectiveDate,
            boolean deleteDerivedCreatedFromItems
    ) {}

    record RecurrenceSpec(String rrule, List<LocalDate> rdates, List<LocalDate> exdates, LocalDate untilDate) {}
    record TimetableSlotSpec(int dayOfWeek, String startTime, String endTime, LocalDate periodStart, LocalDate periodEnd) {}
    record LinkSpec(UUID sourceItemId, UUID targetItemId, String linkType) {}

    record BatchWriteResult(UUID batchId, boolean alreadyProcessed, List<UUID> affectedItemIds, long latestSyncSeq) {}
    record BatchStatusResult(UUID batchId, String status, BatchWriteResult result) {}
}
