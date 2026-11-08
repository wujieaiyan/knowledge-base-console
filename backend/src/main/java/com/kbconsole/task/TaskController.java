package com.kbconsole.task;

import com.kbconsole.common.ApiResponse;
import com.kbconsole.common.PageResult;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/kb/series/{seriesId}/tasks")
@RequiredArgsConstructor
public class TaskController {
    private final TaskService taskService;

    @GetMapping
    public ApiResponse<PageResult<TaskService.TaskView>> list(
            @PathVariable Long seriesId,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String type,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.ok(taskService.list(seriesId, status, type, page, size));
    }

    @GetMapping("/{taskId}")
    public ApiResponse<TaskService.TaskDetail> detail(@PathVariable Long seriesId,
                                                      @PathVariable Long taskId) {
        return ApiResponse.ok(taskService.detail(seriesId, taskId));
    }

    @GetMapping("/{taskId}/logs")
    public ApiResponse<List<TaskService.TaskLogView>> logs(@PathVariable Long seriesId,
                                                           @PathVariable Long taskId) {
        return ApiResponse.ok(taskService.logs(seriesId, taskId));
    }

    @PostMapping("/{taskId}/retry")
    public ApiResponse<TaskService.TaskView> retry(@PathVariable Long seriesId,
                                                   @PathVariable Long taskId) {
        return ApiResponse.ok(taskService.retry(seriesId, taskId));
    }

    @PostMapping("/{taskId}/cancel")
    public ApiResponse<Void> cancel(@PathVariable Long seriesId, @PathVariable Long taskId) {
        taskService.cancel(seriesId, taskId);
        return ApiResponse.ok();
    }
}
