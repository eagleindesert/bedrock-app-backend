package com.bedrock.app.common.response;

public record ApiResponse<T>(
        boolean success,
        T data,
        ErrorBody error
) {
    public static <T> ApiResponse<T> ok(T data) {
        return new ApiResponse<>(true, data, null);
    }

    public static ApiResponse<Void> ok() {
        return new ApiResponse<>(true, null, null);
    }

    public static <T> ApiResponse<T> fail(String code, String message, Object details) {
        return new ApiResponse<>(false, null, new ErrorBody(code, message, details));
    }

    public record ErrorBody(String code, String message, Object details) {}
}
