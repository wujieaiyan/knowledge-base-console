package com.kbconsole.config;

import com.kbconsole.common.ApiResponse;
import com.kbconsole.common.CurrentUser;
import com.kbconsole.common.UserContext;
import com.kbconsole.member.PermissionService;
import com.kbconsole.task.TaskService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/kb/series/{seriesId}")
@RequiredArgsConstructor
public class ConfigController {
    private final ConfigService configService;
    private final PermissionService permissionService;
    private final TaskService taskService;

    @GetMapping("/config")
    public ApiResponse<ConfigService.ConfigResponse> current(@PathVariable Long seriesId) {
        permissionService.requireRead(seriesId);
        return ApiResponse.ok(configService.current(seriesId));
    }

    @GetMapping("/config-versions")
    public ApiResponse<List<ConfigService.ConfigResponse>> versions(@PathVariable Long seriesId) {
        permissionService.requireAdmin(seriesId);
        return ApiResponse.ok(configService.versions(seriesId));
    }

    @GetMapping("/config-versions/{versionId}")
    public ApiResponse<ConfigService.ConfigResponse> version(@PathVariable Long seriesId, @PathVariable Long versionId) {
        permissionService.requireRead(seriesId);
        return ApiResponse.ok(configService.version(seriesId, versionId));
    }

    @PostMapping("/config-versions")
    public ApiResponse<Map<String, Object>> save(@PathVariable Long seriesId, @RequestBody SaveConfigRequest request) {
        CurrentUser user = UserContext.require();
        permissionService.requireAdmin(seriesId);
        ConfigService.SavedConfig saved = configService.createVersion(seriesId, request.getIndexStrategy(),
                request.getParse(), request.getChunk(), request.getRetrieval(), request.getChangeNote(), user.id());

        Long rebuildTaskId = null;
        if (request.getRebuild() != null && request.getRebuild().getScope() != null) {
            String scope = request.getRebuild().getScope().toUpperCase();
            if ("FULL".equals(scope) || "FAILED".equals(scope)) {
                rebuildTaskId = taskService.createRebuild(seriesId, scope, request.getRebuild().getDocumentIds(),
                        saved.versionId(), user);
            }
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("configVersionId", saved.versionId());
        result.put("versionNo", saved.versionNo());
        result.put("rebuildTaskId", rebuildTaskId);
        return ApiResponse.ok(result);
    }
}
