package com.kbconsole.series;

import com.kbconsole.auth.Permissions;
import com.kbconsole.auth.RequirePermission;
import com.kbconsole.common.ApiResponse;
import com.kbconsole.common.PageResult;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/kb/series")
@RequiredArgsConstructor
public class SeriesController {
    private final SeriesService seriesService;

    @GetMapping
    public ApiResponse<PageResult<SeriesView>> list(@RequestParam(defaultValue = "accessible") String scope,
                                                    @RequestParam(required = false) String keyword,
                                                    @RequestParam(required = false) Long deptId,
                                                    @RequestParam(required = false) String status,
                                                    @RequestParam(defaultValue = "1") int page,
                                                    @RequestParam(defaultValue = "12") int size) {
        return ApiResponse.ok(seriesService.list(scope, keyword, deptId, status, page, size));
    }

    @PostMapping
    @RequirePermission(Permissions.KB_CREATE)
    public ApiResponse<SeriesView> create(@Valid @RequestBody CreateSeriesRequest request) {
        return ApiResponse.ok(seriesService.create(request));
    }

    @GetMapping("/{id}")
    public ApiResponse<SeriesView> detail(@PathVariable Long id) {
        return ApiResponse.ok(seriesService.detail(id));
    }

    @PatchMapping("/{id}")
    public ApiResponse<SeriesView> update(@PathVariable Long id, @RequestBody UpdateSeriesRequest request) {
        return ApiResponse.ok(seriesService.update(id, request));
    }

    @PostMapping("/{id}/disable")
    public ApiResponse<Void> disable(@PathVariable Long id) {
        seriesService.changeStatus(id, false);
        return ApiResponse.ok();
    }

    @PostMapping("/{id}/enable")
    public ApiResponse<Void> enable(@PathVariable Long id) {
        seriesService.changeStatus(id, true);
        return ApiResponse.ok();
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable Long id) {
        seriesService.delete(id);
        return ApiResponse.ok();
    }

    @GetMapping("/{id}/stats")
    public ApiResponse<Map<String, Object>> stats(@PathVariable Long id) {
        return ApiResponse.ok(seriesService.stats(id));
    }
}
