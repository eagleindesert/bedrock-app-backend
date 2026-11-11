package com.bedrock.app.common.port;

import java.util.UUID;

/**
 * 명세 F-24 · F-42 · F-45:
 * 반복 마스터 변경이나 ICS 재가져오기로 짝을 잃거나 다른 회차와 겹친 예외 회차를
 * 풀지 않은 충돌(kind = ORPHANED_OCCURRENCE)로 등록한다. 회차는 시간표면 slotId까지 가린다.
 */
public interface ConflictRegistryPort {
    void registerOrphanedOccurrenceConflict(
            UUID userId,
            ItemBatchWritePort.OccurrenceRef occurrence,
            String fieldKey,
            String plainCandidateJson,
            UUID sourceWriteId
    );
}
