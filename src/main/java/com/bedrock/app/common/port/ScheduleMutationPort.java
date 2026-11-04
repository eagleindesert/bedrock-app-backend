package com.bedrock.app.common.port;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 명세 F-23 · F-24 · F-30 · F-33:
 * 묶음 쓰기나 칸 단위 쓰기 도중 발생하는 회차 예외 저장, 반복 마스터 나누기,
 * 시간표 겹침 검사를 처리한다.
 */
public interface ScheduleMutationPort {
    void validateAndSaveTimetableSlots(UUID userId, UUID itemId, UUID collectionId, List<ItemBatchWritePort.TimetableSlotSpec> slots);
    void saveRecurrenceRule(UUID userId, UUID itemId, ItemBatchWritePort.RecurrenceSpec spec);
    UUID applyOccurrenceException(UUID userId, UUID masterItemId, LocalDate occurrenceDate, Map<String, Object> changedFields, boolean deleteOccurrence);
    UUID splitAndModifySeries(UUID userId, UUID masterItemId, LocalDate effectiveFrom, Map<String, Object> changedFields, boolean isTimetable);
}
