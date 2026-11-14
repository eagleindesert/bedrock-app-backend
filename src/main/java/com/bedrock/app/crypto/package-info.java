/**
 * BE 1 담당: 사용자 데이터 키와 KMS 봉투 암호화, UserCryptoPort 구현.
 * 다른 담당 기능은 common.port의 인터페이스를 통해 호출한다.
 *
 * <p>기능 명세 4.4에 따라 사용자 DEK는 DB 밖에 둔다. 초기 공통 스키마의
 * user_data_keys.wrapped_dek는 사용하지 않으며 기존 마이그레이션은 변경하지 않는다.
 * bedrock.crypto.provider=vault일 때만 실제 구현을 등록하고, 설정하지 않으면 공통 Stub이 실패한다.
 * Vault 주소/토큰은 BEDROCK_CRYPTO_VAULT_ADDRESS, BEDROCK_CRYPTO_VAULT_TOKEN으로 주입한다.
 * KMS_KEY_ID는 derived=true인 Transit 키 이름, 별도 KV v2 마운트 기본값은 bedrock-keys다.
 * 애플리케이션에는 datakey/wrapped·decrypt 및 키 KV의 read·create·update·destroy만 허용한다.
 * 사람 계정에는 평소 복호화 권한을 주지 않는다. 키 저장소 백업 7일·DB 백업 35일 및
 * 복원 후 삭제 재적용은 별도의 운영 정책으로 검증해야 한다.
 */
package com.bedrock.app.crypto;
