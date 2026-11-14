/**
 * BE 1 담당: 메일 코드, SMTP 발송 한도와 반송 처리.
 * 다른 담당 기능은 common.port의 인터페이스를 통해 호출한다.
 *
 * <p>BEDROCK_MAIL_CODE_HMAC_KEY에는 코드 저장소 밖의 256비트 이상 난수 키를 Base64로 주입한다.
 * 키가 없으면 코드 기능은 FEATURE_NOT_READY로 실패한다. 기존 SMTP 설정과 MAIL_FROM을 사용한다.
 * 코드·제어 정보는 DB, 이동 구간 발송 한도는 Redis에 둔다. 코드 원문은 DB/Redis에 쓰지 않고
 * 비동기 SMTP 작업의 메모리에만 두므로 프로세스가 종료된 발송은 사용자가 다시 요청해야 한다.
 * 가입/로그인 쪽은 원래 작업을 서버 세션에 보관하고 코드 확인 후에만 비밀번호 정책·해시를 실행한다.
 * 초기 발송과 재시도에 서비스/주소 한도를 적용하며 반송된 주소는 발송하지 않는다.
 * bedrock.mail.delivery 지표의 outcome으로 sent, bounced, failed, cancelled, rate_limited, queue_full을 구분한다.
 */
package com.bedrock.app.mail;
