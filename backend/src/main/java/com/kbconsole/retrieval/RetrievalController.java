package com.kbconsole.retrieval;

import com.kbconsole.auth.Permissions;
import com.kbconsole.auth.RequirePermission;
import com.kbconsole.common.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/kb/series/{seriesId}/retrieval")
@RequiredArgsConstructor
@RequirePermission(Permissions.CHAT_USE)
public class RetrievalController {
    private final RetrievalService retrievalService;

    @PostMapping("/test")
    public ApiResponse<RetrievalService.SearchResponse> test(
            @PathVariable Long seriesId,
            @RequestBody RetrievalService.SearchRequest request) {
        return ApiResponse.ok(retrievalService.search(seriesId, request));
    }
}
