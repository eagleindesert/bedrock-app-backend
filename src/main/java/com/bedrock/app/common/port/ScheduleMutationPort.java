package com.bedrock.app.common.port;

import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;

/**
 * 명세 F-23 · F-24 · F-30 · F-33:
 * 묶음 쓰기나 칸 단위 쓰기 도중 발생하는 회차 예외 저장, 반복 마스터 나누기,
 * 시간표 겹침 검사를 처리한다. writeId는 칸 쓰기 이력(item_field_writes)에 남겨 되돌리기에 쓴다.
 * 회차 날짜 · 적용 시작일 · 예외와 지운 회차 옮기기 · 짝 없는 예외 판정은 마스터 아이템의
 * 시간대(items.time_zone, {@link com.bedrock.app.common.domain.ItemTimeZones})로 계산한다(기능 명세 v0.0.6 F-12 · F-23).
 */
public interface ScheduleMutationPort {
    /** 같은 컬렉션 · 겹치는 기간의 시간표 마스터와 시간대가 겹치면 TIMETABLE_SLOT_OVERLAP으로 거절한다. */
    void validateAndSaveTimetable(UUID userId, UUID itemId, UUID collectionId, ItemBatchWritePort.TimetableSpec timetable);

    void saveRecurrenceRule(UUID userId, UUID itemId, ItemBatchWritePort.RecurrenceSpec spec);

    UUID applyOccurrenceException(UUID userId, ItemBatchWritePort.OccurrenceRef occurrence,
            Map<String, Object> changedFields, boolean deleteOccurrence, UUID writeId);

    /** 나누면서 짝을 잃은 회차 예외는 ConflictRegistryPort로 ORPHANED_OCCURRENCE 충돌을 등록한다. */
    UUID splitAndModifySeries(UUID userId, UUID masterItemId, LocalDate effectiveFrom,
            Map<String, Object> changedFields, boolean isTimetable, UUID writeId);
}
