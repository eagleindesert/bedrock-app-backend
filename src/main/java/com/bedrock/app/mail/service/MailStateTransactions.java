package com.bedrock.app.mail.service;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.function.Supplier;

/** 세션 교체·주소별 실패 횟수·코드 소비를 짧은 DB 트랜잭션으로 직렬화한다. */
@Component("mailStateTransactions")
public class MailStateTransactions {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;

    public MailStateTransactions(JdbcTemplate jdbc, PlatformTransactionManager manager) {
        this.jdbc = jdbc;
        this.transaction = new TransactionTemplate(manager);
        // 상위 인증 트랜잭션이 실패해도 오답/소비 기록이 되돌아가면 안 된다.
        this.transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    public <T> T locked(Supplier<T> operation) {
        return transaction.execute(status -> {
            // 한 세션의 이메일 변경과 동일 주소의 다른 세션 가입이 교차해도 교착하지 않는다.
            // 이 구간에서 SMTP/KMS/Redis 등 외부 요청이나 비밀번호 해시는 실행하지 않는다.
            jdbc.execute("SELECT pg_advisory_xact_lock(51680445298271)");
            return operation.get();
        });
    }
}
