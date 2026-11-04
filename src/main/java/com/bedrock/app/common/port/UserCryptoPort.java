package com.bedrock.app.common.port;

import java.util.UUID;

/**
 * 명세 4.4: 사람이 적는 글(이름 · 본문 · 장소 · 태그 · 가져온 ICS 값 · 컬렉션 이름 · 지침 · 이력 · 충돌 · 대화 기록)을
 * 사용자별 데이터 키(DEK + KMS)로 암호화하고 복호화한다.
 */
public interface UserCryptoPort {
    void createDataKeyForUser(UUID userId);
    String encrypt(UUID userId, String plainText);
    String decrypt(UUID userId, String cipherText);
    void deleteDataKeyForUser(UUID userId);
}
