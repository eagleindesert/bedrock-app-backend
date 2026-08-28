package com.bedrock.app.action.controller;

import com.bedrock.app.action.application.ActionService;
import com.bedrock.app.action.dto.ActionRequests;
import com.bedrock.app.action.dto.ActionResponse;
import com.bedrock.app.common.exception.ErrorResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@Tag(
        name = "Action",
        description = "Action 은 하나 이상의 Block 을 조합한 Item 생성용 입력 정의다. "
                + "실행하면 여러 Block 의 필드를 한 번에 입력받아 하나의 Item 을 만든다. "
                + "모든 엔드포인트가 인증을 요구한다."
)
@RestController
@RequestMapping("/api/v1/actions")
@RequiredArgsConstructor
public class ActionController {

    private final ActionService actionService;

    @Operation(
            summary = "액션 목록 조회",
            description = """
                    scope 값에 따라 다음 목록을 반환한다 (생략 시 available).
                    - available: 현재 사용자가 만든 Action 과 실행 가능한 Preset 전체
                    - me: 현재 사용자가 만든 비프리셋 Action
                    - preset: 실행 가능한 Preset 전체
                    """
    )
    @ApiResponse(responseCode = "200", description = "조회 성공")
    @ApiResponse(responseCode = "400", description = "잘못된 scope 값",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @ApiResponse(responseCode = "401", description = "인증되지 않음", content = @Content)
    @GetMapping
    public ResponseEntity<List<ActionResponse>> findAll(
            @AuthenticationPrincipal Long ownerId,
            @Parameter(description = "조회 범위", example = "available",
                    schema = @Schema(allowableValues = {"available", "me", "preset"}))
            @RequestParam(defaultValue = "available") String scope
    ) {
        return ResponseEntity.ok(
                actionService.findAll(ownerId, scope)
        );
    }

    @Operation(
            summary = "액션 생성",
            description = "Block 구성을 포함한 Action 을 생성한다. "
                    + "blocks[].displayOrder 는 UI 표시 순서일 뿐 실행 순서가 아니다. "
                    + "preset=true 생성은 관리자만 가능하다."
    )
    @ApiResponse(responseCode = "201", description = "생성 성공")
    @ApiResponse(responseCode = "400", description = "잘못된 Block 구성 또는 요청 값 검증 실패",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @ApiResponse(responseCode = "401", description = "인증되지 않음", content = @Content)
    @ApiResponse(responseCode = "403", description = "일반 사용자의 Preset 생성",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @PostMapping
    public ResponseEntity<ActionResponse> create(
            @AuthenticationPrincipal Long ownerId,
            Authentication authentication,
            @Valid @RequestBody ActionRequests.Create request
    ) {
        ActionResponse response =
                actionService.create(ownerId, isAdmin(authentication), request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @Operation(
            summary = "액션 단건 조회",
            description = "입력 폼 정의를 조회한다. blocks[].fields 는 각 Block 의 원본 필드이고, "
                    + "inputFields 는 여러 Block 의 같은 키를 한 번만 남긴 실제 단일 입력 폼 계약이다."
    )
    @ApiResponse(responseCode = "200", description = "조회 성공")
    @ApiResponse(responseCode = "401", description = "인증되지 않음", content = @Content)
    @ApiResponse(responseCode = "404", description = "조회할 수 없는 Action",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @GetMapping("/{actionId}")
    public ResponseEntity<ActionResponse> findOne(
            @AuthenticationPrincipal Long ownerId,
            @Parameter(description = "액션 ID", example = "835c78c3-8d10-4f26-a40b-9c8cf0599886")
            @PathVariable UUID actionId
    ) {
        return ResponseEntity.ok(actionService.findOne(actionId, ownerId));
    }

    @Operation(
            summary = "액션 수정",
            description = "전달한 필드만 부분 수정한다. blocks 를 전달하면 Block 구성 전체가 교체된다."
    )
    @ApiResponse(responseCode = "200", description = "수정 성공")
    @ApiResponse(responseCode = "400", description = "잘못된 Block 구성 또는 요청 값 검증 실패",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @ApiResponse(responseCode = "401", description = "인증되지 않음", content = @Content)
    @ApiResponse(responseCode = "403", description = "다른 사용자의 Action 또는 Preset 변경",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @ApiResponse(responseCode = "404", description = "조회할 수 없는 Action",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @PatchMapping("/{actionId}")
    public ResponseEntity<ActionResponse> update(
            @AuthenticationPrincipal Long ownerId,
            Authentication authentication,
            @Parameter(description = "액션 ID", example = "835c78c3-8d10-4f26-a40b-9c8cf0599886")
            @PathVariable UUID actionId,
            @Valid @RequestBody ActionRequests.Update request
    ) {
        return ResponseEntity.ok(
                actionService.update(
                        actionId,
                        ownerId,
                        isAdmin(authentication),
                        request
                )
        );
    }

    @Operation(summary = "액션 삭제", description = "Action 을 삭제한다.")
    @ApiResponse(responseCode = "204", description = "삭제 성공")
    @ApiResponse(responseCode = "401", description = "인증되지 않음", content = @Content)
    @ApiResponse(responseCode = "403", description = "다른 사용자의 Action 또는 Preset 삭제",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @ApiResponse(responseCode = "404", description = "조회할 수 없는 Action",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @DeleteMapping("/{actionId}")
    public ResponseEntity<Void> delete(
            @AuthenticationPrincipal Long ownerId,
            Authentication authentication,
            @Parameter(description = "액션 ID", example = "835c78c3-8d10-4f26-a40b-9c8cf0599886")
            @PathVariable UUID actionId
    ) {
        actionService.delete(actionId, ownerId, isAdmin(authentication));
        return ResponseEntity.noContent().build();
    }

    @Operation(
            summary = "액션 실행",
            description = """
                    입력 값을 병합해 targetCollectionId 컬렉션에 Item 을 생성한다.
                    병합 우선순위는 `Block 기본값 < triggerPayload < input` 이다.
                    응답에는 생성된 Item 전체가 포함되며, itemId 로 Item DELETE 를 호출해 실행을 취소할 수 있다.
                    """
    )
    @ApiResponse(responseCode = "200", description = "실행 성공 (Item 생성됨)")
    @ApiResponse(responseCode = "400", description = "알 수 없는 입력 필드, 필수값 누락, 자료형/enum 값 오류",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @ApiResponse(responseCode = "401", description = "인증되지 않음", content = @Content)
    @ApiResponse(responseCode = "404", description = "실행할 수 없는 Action",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @ApiResponse(responseCode = "409", description = "비활성 Action 실행",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @PostMapping("/{actionId}/execute")
    public ResponseEntity<ActionResponse.Execution> execute(
            @AuthenticationPrincipal Long ownerId,
            @Parameter(description = "액션 ID", example = "835c78c3-8d10-4f26-a40b-9c8cf0599886")
            @PathVariable UUID actionId,
            @Valid @RequestBody ActionRequests.Execute request
    ) {
        return ResponseEntity.ok(
                actionService.execute(actionId, ownerId, request)
        );
    }

    private boolean isAdmin(Authentication authentication) {
        if (authentication == null) {
            return false;
        }
        for (GrantedAuthority authority : authentication.getAuthorities()) {
            if ("ROLE_ADMIN".equals(authority.getAuthority())) {
                return true;
            }
        }
        return false;
    }
}
