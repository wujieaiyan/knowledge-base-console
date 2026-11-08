package com.kbconsole.directory;

import com.kbconsole.common.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/kb/series/{seriesId}/directories")
@RequiredArgsConstructor
public class DirectoryController {
    private final DirectoryService directoryService;

    @GetMapping("/tree")
    public ApiResponse<List<DirectoryService.DirectoryNode>> tree(@PathVariable Long seriesId) {
        return ApiResponse.ok(directoryService.tree(seriesId));
    }

    @PostMapping
    public ApiResponse<DirectoryService.DirectoryNode> create(@PathVariable Long seriesId,
                                                              @RequestBody DirectoryService.CreateDirectoryRequest request) {
        return ApiResponse.ok(directoryService.create(seriesId, request));
    }

    @PatchMapping("/{directoryId}")
    public ApiResponse<DirectoryService.DirectoryNode> rename(@PathVariable Long seriesId, @PathVariable Long directoryId,
                                                              @RequestBody DirectoryService.RenameDirectoryRequest request) {
        return ApiResponse.ok(directoryService.rename(seriesId, directoryId, request));
    }

    @PostMapping("/{directoryId}/move")
    public ApiResponse<Void> move(@PathVariable Long seriesId, @PathVariable Long directoryId,
                                  @RequestBody DirectoryService.MoveDirectoryRequest request) {
        directoryService.move(seriesId, directoryId, request);
        return ApiResponse.ok();
    }

    @DeleteMapping("/{directoryId}")
    public ApiResponse<Void> delete(@PathVariable Long seriesId, @PathVariable Long directoryId,
                                    @RequestParam(required = false) String strategy,
                                    @RequestParam(required = false) String confirmName) {
        directoryService.delete(seriesId, directoryId, strategy, confirmName);
        return ApiResponse.ok();
    }
}
