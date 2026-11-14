package com.bedrock.app.crypto.key;

import java.util.UUID;

/** 사용자별 DEK는 애플리케이션 DB 밖에서 KMS로 감싸 보관한다. */
public interface UserDataKeyProvider {
    /** 재시도해도 기존 키를 교체하지 않는다. 삭제된 사용자 ID에는 키를 다시 만들지 않는다. */
    void createIfAbsent(UUID userId);

    /** 호출자가 사용 직후 지워야 하는, 새로 할당된 256비트 키를 반환한다. */
    byte[] load(UUID userId);

    /** 모든 키 버전을 영구 파기한다. 재호출해도 안전하며 삭제 표식은 유지한다. */
    void delete(UUID userId);
}
