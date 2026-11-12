package com.bedrock.app.common.port;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 명세 F-43 · F-18 · F-31 · F-34 · F-42 · F-49:
 * 만들기 · 고치기 · 지우기 · 회차 예외 · 반복 나누기 · 미루기를 한 묶음(전부 아니면 없음)으로 실행하고,
 * (userId, batchId)로 재전송 중복 적용을 막는다. 같은 aiRequestId는 묶음 ID가 달라도 한 번만 완료한다.
 * 필드 구성은 「API 명세 합의 v1.0」의 BatchWriteRequest를 따른다.
 */
public interface ItemBatchWritePort {

    BatchWriteResult executeAtomicBatch(UUID userId, BatchWriteCommand command);

    BatchStatusResult getBatchStatus(UUID userId, UUID batchId);

    record BatchWriteCommand(
            UUID batchId,
            String batchType, // AI_MUTATION | TIMETABLE_CREATE | TODO_POSTPONE | ICS_IMPORT | BULK_DELETE
            UUID aiRequestId, // AI 저장일 때만
            String expectedAiRevision, // AI 저장일 때만
            UUID conversationId, // 대화 기록과 이어진 저장일 때
            CollectionCreateSpec newCollection, // TIMETABLE_CREATE에서만
            List<ItemCreateSpec> creates,
            List<ItemPatchSpec> patches,
            List<ItemDeleteSpec> deletes,
            List<LinkSpec> linksToUpsert,
            List<PostponeSpec> postpones, // 여러 할 일을 한 묶음으로 미룰 수 있다(F-18 · F-43)
            PendingInboxCompletion pendingInboxCompletion // 정리 대기 재시도 완료일 때(F-49)
    ) {}

    /**
     * 반복 회차의 정체성. 시간표 회차는 slotId까지, 일반 반복은 slotId가 null이다.
     * recurrenceDate는 마스터 아이템 시간대(timeZone)의 날짜다. 시간표 회차는 시간대 없는 요일의 날짜다.
     */
    record OccurrenceRef(UUID masterItemId, LocalDate recurrenceDate, UUID slotId) {}

    record ItemTarget(UUID itemId, OccurrenceRef occurrence) {}

    record ItemCreateSpec(
            UUID itemId,
            UUID collectionId, // null이면 기본 컬렉션
            String inputConfigCode,
            Map<String, Object> fields,
            RecurrenceSpec recurrence,
            TimetableSpec timetable,
            List<UUID> referenceTargetIds
    ) {}

    record ItemPatchSpec(
            UUID operationId,
            UUID itemId,
            String scope, // SINGLE | THIS_OCCURRENCE | THIS_AND_FUTURE | ALL
            OccurrenceRef occurrence,
            LocalDate effectiveDate,
            Map<String, UUID> baseFieldVersions, // 칸별 기준 쓰기. 값 null은 이전 쓰기가 없던 칸
            Map<String, Object> changedFields,
            List<UUID> referenceTargetIds, // 본문이 바뀔 때만. null과 빈 목록을 가른다
            boolean materializeForLink
    ) {}

    record ItemDeleteSpec(
            UUID operationId,
            UUID itemId,
            String scope, // SINGLE | THIS_OCCURRENCE | THIS_AND_FUTURE | ALL
            OccurrenceRef occurrence,
            LocalDate effectiveDate,
            Map<String, UUID> baseFieldVersions,
            boolean deleteDerivedCreatedFromItems
    ) {}

    record PostponeSpec(
            UUID operationId,
            ItemTarget source,
            String mode, // TO_DATE | SKIP_TO_NEXT
            UUID carryItemId,
            ScheduleValue destination,
            Map<String, UUID> baseFieldVersions
    ) {}

    record ScheduleValue(boolean isAllDay, Instant startAt, Instant endAt, LocalDate startDate, LocalDate endDate) {}

    record PendingInboxCompletion(
            UUID itemId,
            Map<String, UUID> baseFieldVersions,
            String action // TRASH_ITEM | CLEAR_PENDING_FLAG
    ) {}

    record CollectionCreateSpec(UUID collectionId, String name, String color, String icon) {}

    /** rdates · exdates · untilDate · effectiveFrom은 마스터 아이템 시간대(timeZone)의 날짜다. */
    record RecurrenceSpec(
            String rrule,
            List<LocalDate> rdates,
            List<LocalDate> exdates,
            LocalDate untilDate,
            LocalDate effectiveFrom
    ) {}

    /** 시간표 아이템의 기간은 아이템마다 하나이고, 시간대는 slotId로 가린다(F-30). */
    record TimetableSpec(List<TimetableSlotSpec> slots, LocalDate periodStart, LocalDate periodEnd) {}

    record TimetableSlotSpec(UUID slotId, int dayOfWeek, LocalTime startTime, LocalTime endTime) {}

    record LinkSpec(UUID sourceItemId, UUID targetItemId, String linkType) {}

    record MutationResult(
            UUID resultId,
            UUID operationId,
            UUID itemId,
            List<UUID> affectedItemIds,
            String scope,
            List<String> changedFieldKeys,
            UUID trashBundleId,
            boolean undoSupported
    ) {}

    record BatchWriteResult(
            UUID batchId,
            boolean alreadyProcessed,
            List<UUID> affectedItemIds,
            List<MutationResult> results,
            long latestSyncSeq
    ) {}

    record BatchStatusResult(UUID batchId, String status, BatchWriteResult result) {}
}
