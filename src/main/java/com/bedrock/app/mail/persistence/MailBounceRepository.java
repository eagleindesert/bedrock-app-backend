package com.bedrock.app.mail.persistence;

import com.bedrock.app.mail.domain.MailBounce;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MailBounceRepository extends JpaRepository<MailBounce, String> {}
