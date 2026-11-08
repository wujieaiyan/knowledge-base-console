package com.kbconsole.common;

import java.util.List;
import java.util.Map;

public record ApiResponse<T>(String code, String message, T data, String traceId) {
    public static <T> ApiResponse<T> ok(T data) {
        return new ApiResponse<>("0", "OK", data, TraceIdFilter.currentTraceId());
    }

    public static <T> ApiResponse<T> ok() {
        return ok(null);
    }

    public static <T> ApiResponse<T> error(String code, String message) {
        return new ApiResponse<>(code, message, null, TraceIdFilter.currentTraceId());
    }

    public record ErrorResponse(String code, String message, List<Map<String, String>> details, String traceId) {
    }
}
