package com.kbconsole.auth;

import com.kbconsole.common.ApiException;
import com.kbconsole.common.CurrentUser;
import com.kbconsole.common.ErrorCodes;
import com.kbconsole.common.UserContext;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

import java.util.Arrays;
import java.util.Set;

@Component
@RequiredArgsConstructor
public class AuthInterceptor implements HandlerInterceptor {
    private final TokenService tokenService;
    private final AppUserRepository userRepository;
    private final AuthorizationService authorizationService;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
            return true;
        }
        String header = request.getHeader("Authorization");
        if (header == null || !header.startsWith("Bearer ")) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, ErrorCodes.UNAUTHORIZED, "请先登录");
        }
        Long userId = tokenService.verify(header.substring(7).trim());
        AppUser user = userRepository.findById(userId)
                .orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED, ErrorCodes.UNAUTHORIZED, "账号不存在或已停用"));
        if (!user.isActive()) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, ErrorCodes.UNAUTHORIZED, "账号已停用，请联系管理员");
        }

        Set<String> permissions = authorizationService.permissionsOf(user);
        UserContext.set(new CurrentUser(user.getId(), user.getName(), user.getAccount(), user.getDeptId(),
                user.isPlatformAdmin(), authorizationService.roleCodeOf(user), permissions));

        RequirePermission required = resolvePermission(handler);
        if (required != null) {
            boolean allowed = required.any()
                    ? Arrays.stream(required.value()).anyMatch(permissions::contains)
                    : Arrays.stream(required.value()).allMatch(permissions::contains);
            if (!allowed) {
                throw new ApiException(HttpStatus.FORBIDDEN, ErrorCodes.FORBIDDEN, "没有执行该操作的权限");
            }
        }
        return true;
    }

    private RequirePermission resolvePermission(Object handler) {
        if (!(handler instanceof HandlerMethod method)) {
            return null;
        }
        RequirePermission required = method.getMethodAnnotation(RequirePermission.class);
        return required != null ? required : method.getBeanType().getAnnotation(RequirePermission.class);
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) {
        UserContext.clear();
    }
}
