package com.kbconsole.common;

public final class ErrorCodes {
    private ErrorCodes() {
    }

    public static final String VALIDATION = "KB_40001";
    public static final String INVALID_CONFIG = "KB_40002";
    public static final String UNSUPPORTED_TYPE = "KB_40003";
    public static final String FILE_TOO_LARGE = "KB_40004";
    public static final String INVALID_REGEX = "KB_40005";
    public static final String DIRECTORY_DEPTH = "KB_40006";
    public static final String DUPLICATE_DIRECTORY = "KB_40007";
    public static final String DUPLICATE_TAG = "KB_40008";
    public static final String LAST_OWNER = "KB_40009";
    public static final String INHERITED_MEMBER = "KB_40010";
    public static final String INVALID_PARENT = "KB_40011";
    public static final String DEPARTMENT_DEPTH = "KB_40012";
    public static final String ROLE_IMMUTABLE = "KB_40013";

    public static final String UNAUTHORIZED = "KB_40101";
    public static final String FORBIDDEN = "KB_40301";
    public static final String SERIES_LOCKED = "KB_40302";
    public static final String FILE_SCAN_FAILED = "KB_40303";
    public static final String SELF_OPERATION = "KB_40304";
    public static final String LAST_ADMIN = "KB_40305";

    public static final String RESOURCE_NOT_FOUND = "KB_40400";
    public static final String SERIES_NOT_FOUND = "KB_40401";
    public static final String DOCUMENT_NOT_FOUND = "KB_40402";
    public static final String DIRECTORY_NOT_FOUND = "KB_40403";
    public static final String TASK_NOT_FOUND = "KB_40404";
    public static final String USER_NOT_FOUND = "KB_40405";
    public static final String ROLE_NOT_FOUND = "KB_40406";
    public static final String DEPARTMENT_NOT_FOUND = "KB_40407";
    public static final String SESSION_NOT_FOUND = "KB_40408";

    public static final String VERSION_CONFLICT = "KB_40901";
    public static final String DUPLICATE_DOCUMENT = "KB_40902";
    public static final String TASK_CONFLICT = "KB_40903";
    public static final String SERIES_DELETING = "KB_40904";
    public static final String DIRECTORY_NOT_EMPTY = "KB_40905";
    public static final String IDEMPOTENCY_CONFLICT = "KB_40906";
    public static final String DIRECTORY_CYCLE = "KB_40907";
    public static final String DUPLICATE_ACCOUNT = "KB_40908";
    public static final String DUPLICATE_ROLE = "KB_40909";
    public static final String ROLE_IN_USE = "KB_40910";
    public static final String DEPARTMENT_IN_USE = "KB_40911";
    public static final String DUPLICATE_DEPARTMENT = "KB_40912";
    public static final String DEPARTMENT_CYCLE = "KB_40913";

    public static final String PARSE_FAILED = "KB_42201";
    public static final String NO_TEXT = "KB_42202";
    public static final String OCR_UNAVAILABLE = "KB_42203";
    public static final String EMPTY_DOCUMENT = "KB_42204";
    public static final String DOCUMENT_TOO_LARGE = "KB_42205";
    public static final String UNSUPPORTED_CONTENT = "KB_42206";

    public static final String INTERNAL = "KB_50001";
    public static final String SERVICE_UNAVAILABLE = "KB_50301";
}
