package com.bedrock.app;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.security.autoconfigure.UserDetailsServiceAutoConfiguration;

// 기본 계정과 임시 비밀번호 로그 생성을 막는다.
@SpringBootApplication(exclude = UserDetailsServiceAutoConfiguration.class)
public class AppApplication {

	public static void main(String[] args) {
		SpringApplication.run(AppApplication.class, args);
	}

}
