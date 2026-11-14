package com.bedrock.app.conversation.api;

import com.bedrock.app.ai.service.AiValidation;
import com.bedrock.app.common.auth.CurrentUser;
import com.bedrock.app.common.auth.SessionPrincipal;
import com.bedrock.app.common.response.ApiResponse;
import com.bedrock.app.conversation.dto.ConversationDtos;
import com.bedrock.app.conversation.service.ConversationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.*;
import tools.jackson.databind.JsonNode;

import java.util.UUID;

@RestController("conversationController")
@RequestMapping("/api/v1/conversations")
@Tag(name = "대화 기록", description = "사용자별 암호화 기록·최근순 페이지·영구 삭제")
public class ConversationController {
    private final ConversationService conversations;
    private final AiValidation validation;
    public ConversationController(ConversationService conversations, AiValidation validation) { this.conversations = conversations; this.validation = validation; }

    @GetMapping
    @Operation(summary = "최근 대화 기록 목록", description = "최대 100개를 최근순으로 반환한다. 워크스페이스 초기 응답과 분리된 커서 페이지다.")
    public ConversationDtos.Page<ConversationDtos.Summary> list(@CurrentUser SessionPrincipal user,
            @RequestParam(required = false) String cursor, @RequestParam(defaultValue = "30") int limit) { return conversations.page(user.userId(), cursor, limit); }

    @PutMapping("/{conversationId}")
    @Operation(summary = "대화 기록 생성·갱신", description = "writeId 재전송을 중복 적용하지 않는다. 삭제된 ID는 되살릴 수 없고 실제 묶음 결과만 참조할 수 있다.")
    public ConversationDtos.Detail put(@CurrentUser SessionPrincipal user, @PathVariable UUID conversationId, @RequestBody JsonNode body) {
        return conversations.put(user.userId(), conversationId, validation.readRequest(body, ConversationDtos.PutRequest.class));
    }

    @GetMapping("/{conversationId}")
    @Operation(summary = "대화 기록 상세", description = "소유권을 확인한 기록을 복호화한다. 조회 결과 목록은 저장하거나 반환하지 않는다.")
    public ConversationDtos.Detail get(@CurrentUser SessionPrincipal user, @PathVariable UUID conversationId) { return conversations.get(user.userId(), conversationId); }

    @DeleteMapping("/{conversationId}")
    @Operation(summary = "대화 하나 영구 삭제", description = "대기 질문과 대화 글을 지우고 삭제 신호를 남긴다. 대화가 만든 아이템은 유지한다.")
    public ApiResponse<Void> delete(@CurrentUser SessionPrincipal user, @PathVariable UUID conversationId, @RequestParam UUID writeId) {
        conversations.delete(user.userId(), conversationId, writeId); return ApiResponse.ok();
    }

    @DeleteMapping
    @Operation(summary = "대화 전체 영구 삭제", description = "confirmed=true가 필요하다. 같은 writeId를 재전송해도 이후 새로 만든 기록을 지우지 않는다.")
    public ApiResponse<Void> deleteAll(@CurrentUser SessionPrincipal user, @RequestParam UUID writeId, @RequestParam boolean confirmed) {
        conversations.deleteAll(user.userId(), writeId, confirmed); return ApiResponse.ok();
    }
}
