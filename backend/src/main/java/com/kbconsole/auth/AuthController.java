package com.kbconsole.auth;

import com.kbconsole.common.ApiResponse;
import com.kbconsole.common.ApiException;
import com.kbconsole.common.CurrentUser;
import com.kbconsole.common.ErrorCodes;
import com.kbconsole.common.UserContext;
import com.kbconsole.org.Department;
import com.kbconsole.org.DepartmentRepository;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.List;

@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {
    private final AppUserRepository userRepository;
    private final DepartmentRepository departmentRepository;
    private final PasswordEncoder passwordEncoder;
    private final TokenService tokenService;
    private final AuthorizationService authorizationService;

    public record LoginRequest(@NotBlank String account, @NotBlank String password) {
    }

    public record UserInfo(Long id, String account, String name, Long deptId, String deptName,
                           boolean platformAdmin, boolean active, String roleCode, String roleName,
                           List<String> permissions) {
    }

    public record LoginResponse(String token, UserInfo user) {
    }

    @PostMapping("/login")
    public ApiResponse<LoginResponse> login(@Valid @RequestBody LoginRequest request) {
        AppUser user = userRepository.findByAccount(request.account().trim())
                .orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED, ErrorCodes.UNAUTHORIZED, "账号或密码错误"));
        if (!passwordEncoder.matches(request.password(), user.getPasswordHash())) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, ErrorCodes.UNAUTHORIZED, "账号或密码错误");
        }
        if (!user.isActive()) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, ErrorCodes.UNAUTHORIZED, "账号已停用，请联系管理员");
        }
        user.setLastLoginAt(Instant.now());
        user.setUpdatedAt(Instant.now());
        userRepository.save(user);
        return ApiResponse.ok(new LoginResponse(tokenService.issue(user.getId()), toInfo(user)));
    }

    @GetMapping("/me")
    public ApiResponse<UserInfo> me() {
        CurrentUser current = UserContext.require();
        AppUser user = userRepository.findById(current.id())
                .orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED, ErrorCodes.UNAUTHORIZED, "账号不存在"));
        return ApiResponse.ok(toInfo(user));
    }

    @PostMapping("/logout")
    public ApiResponse<Void> logout() {
        return ApiResponse.ok();
    }

    private UserInfo toInfo(AppUser user) {
        String deptName = user.getDeptId() == null ? null
                : departmentRepository.findById(user.getDeptId()).map(Department::getName).orElse(null);
        String roleCode = authorizationService.roleCodeOf(user);
        java.util.Set<String> granted = authorizationService.permissionsOf(user);
        List<String> permissions = Permissions.ALL.stream().filter(granted::contains).toList();
        return new UserInfo(user.getId(), user.getAccount(), user.getName(), user.getDeptId(), deptName,
                user.isPlatformAdmin(), user.isActive(), roleCode,
                authorizationService.roleNameOf(roleCode), permissions);
    }
}
