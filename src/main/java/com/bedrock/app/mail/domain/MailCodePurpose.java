package com.bedrock.app.mail.domain;

public enum MailCodePurpose {
    SIGNUP("이메일 가입"),
    PASSWORD_RESET("비밀번호 재설정"),
    OAUTH_FIRST_LINK("첫 소셜 로그인"),
    ACCOUNT_RESTORE("계정 되살리기"),
    ACCOUNT_RESTART("계정 새로 시작하기");

    private final String description;

    MailCodePurpose(String description) {
        this.description = description;
    }

    public String description() {
        return description;
    }
}
