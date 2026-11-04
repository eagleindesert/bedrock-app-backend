package com.bedrock.app.common.error;

import org.springframework.http.HttpStatus;

public interface ErrorCode {
    String name();
    HttpStatus status();
    String message();
}
