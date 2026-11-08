package com.kbconsole.tag;

import com.kbconsole.common.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/kb/series/{seriesId}/tags")
@RequiredArgsConstructor
public class TagController {
    private final TagService tagService;

    @GetMapping
    public ApiResponse<List<TagService.TagView>> list(@PathVariable Long seriesId) {
        return ApiResponse.ok(tagService.list(seriesId));
    }

    @PostMapping
    public ApiResponse<TagService.TagView> create(@PathVariable Long seriesId,
                                                  @RequestBody TagService.CreateTagRequest request) {
        return ApiResponse.ok(tagService.create(seriesId, request));
    }

    @PatchMapping("/{tagId}")
    public ApiResponse<TagService.TagView> rename(@PathVariable Long seriesId, @PathVariable Long tagId,
                                                  @RequestBody TagService.RenameTagRequest request) {
        return ApiResponse.ok(tagService.rename(seriesId, tagId, request));
    }

    @DeleteMapping("/{tagId}")
    public ApiResponse<Void> delete(@PathVariable Long seriesId, @PathVariable Long tagId) {
        tagService.delete(seriesId, tagId);
        return ApiResponse.ok();
    }
}
