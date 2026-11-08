package com.kbconsole.member;

import com.kbconsole.common.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/kb/series/{seriesId}/members")
@RequiredArgsConstructor
public class MemberController {
    private final MemberService memberService;

    @GetMapping
    public ApiResponse<List<MemberService.MemberView>> list(@PathVariable Long seriesId) {
        return ApiResponse.ok(memberService.list(seriesId));
    }

    @PostMapping
    public ApiResponse<List<MemberService.MemberView>> grant(@PathVariable Long seriesId,
                                                             @RequestBody MemberService.GrantBatchRequest request) {
        return ApiResponse.ok(memberService.grant(seriesId, request));
    }

    @PatchMapping("/{memberId}")
    public ApiResponse<MemberService.MemberView> updateRole(@PathVariable Long seriesId, @PathVariable Long memberId,
                                                            @RequestBody MemberService.UpdateRoleRequest request) {
        return ApiResponse.ok(memberService.updateRole(seriesId, memberId, request));
    }

    @DeleteMapping("/{memberId}")
    public ApiResponse<Void> remove(@PathVariable Long seriesId, @PathVariable Long memberId) {
        memberService.remove(seriesId, memberId);
        return ApiResponse.ok();
    }
}
