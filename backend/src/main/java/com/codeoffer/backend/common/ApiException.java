package com.codeoffer.backend.common;

import org.springframework.http.HttpStatus;

/** 业务异常:携带 HTTP 状态码与面向用户的中文消息,由 GlobalExceptionHandler 统一渲染。 */
public class ApiException extends RuntimeException {

    private final HttpStatus status;

    public ApiException(HttpStatus status, String message) {
        super(message);
        this.status = status;
    }

    public HttpStatus getStatus() {
        return status;
    }
}