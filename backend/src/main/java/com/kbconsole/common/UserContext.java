package com.kbconsole.common;

import org.springframework.http.HttpStatus;

public final class UserContext {
    private static final ThreadLocal<CurrentUser> HOLDER = new ThreadLocal<>();

    private UserContext() {
    }

    public static void set(CurrentUser user) {
        HOLDER.set(user);
    }

    public static CurrentUser get() {
        return HOLDER.get();
    }

    public static CurrentUser require() {
        CurrentUser user = HOLDER.get();
        if (user == null) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, ErrorCodes.UNAUTHORIZED, "登录已失效，请重新登录");
        }
        return user;
    }

    public static void clear() {
        HOLDER.remove();
    }
}
