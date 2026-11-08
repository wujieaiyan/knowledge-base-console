package com.kbconsole.common;

import lombok.Getter;
import org.springframework.http.HttpStatus;

import java.util.List;
import java.util.Map;

@Getter
public class ApiException extends RuntimeException {
    private final HttpStatus status;
    private final String code;
    private final List<Map<String, String>> details;

    public ApiException(HttpStatus status, String code, String message) {
        this(status, code, message, List.of());
    }

    public ApiException(HttpStatus status, String code, String message, List<Map<String, String>> details) {
        super(message);
        this.status = status;
        this.code = code;
        this.details = details;
    }
}
