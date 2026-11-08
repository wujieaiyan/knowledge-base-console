package com.kbconsole.chat;

import com.kbconsole.auth.Permissions;
import com.kbconsole.auth.RequirePermission;
import com.kbconsole.common.ApiResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/chat/sessions")
@RequiredArgsConstructor
@RequirePermission(Permissions.CHAT_USE)
public class ChatController {
    private final ChatService chatService;

    @GetMapping
    public ApiResponse<List<ChatService.SessionSummary>> list() {
        return ApiResponse.ok(chatService.listSessions());
    }

    @PostMapping
    public ApiResponse<ChatService.SessionView> create(@Valid @RequestBody ChatService.CreateSessionRequest request) {
        return ApiResponse.ok(chatService.createSession(request.seriesId()));
    }

    @PutMapping("/{id}")
    public ApiResponse<ChatService.SessionView> rename(@PathVariable Long id,
                                                       @Valid @RequestBody ChatService.RenameSessionRequest request) {
        return ApiResponse.ok(chatService.renameSession(id, request.title()));
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable Long id) {
        chatService.deleteSession(id);
        return ApiResponse.ok();
    }

    @GetMapping("/{id}/messages")
    public ApiResponse<List<ChatService.MessageView>> messages(@PathVariable Long id) {
        return ApiResponse.ok(chatService.messages(id));
    }

    @PostMapping("/{id}/messages")
    public ApiResponse<ChatService.SendMessageResponse> send(@PathVariable Long id,
                                                             @Valid @RequestBody ChatService.SendMessageRequest request) {
        return ApiResponse.ok(chatService.sendMessage(id, request.content()));
    }
}
