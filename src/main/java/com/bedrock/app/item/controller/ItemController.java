package com.bedrock.app.item.controller;

import com.bedrock.app.item.dto.request.ItemCreateRequest;
import com.bedrock.app.item.dto.request.ItemUpdateRequest;
import com.bedrock.app.item.dto.response.ItemResponse;
import com.bedrock.app.item.service.ItemService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@Tag(
        name = "Item",
        description = "아이템 CRUD API. 모든 엔드포인트가 인증을 요구하며, "
                + "소유자(ownerId)는 요청 본문이 아니라 로그인 세션에서 자동 지정된다. "
                + "존재하지 않거나 타인 소유 리소스 접근 시 404 를 반환한다."
)
@RestController
@RequestMapping("/api/v1/items")
@RequiredArgsConstructor
public class ItemController {

    private final ItemService itemService;

    @Operation(
            summary = "아이템 생성",
            description = "현재 사용자 소유의 아이템을 생성한다. attributes 는 임의의 JSON 객체다."
    )
    @ApiResponse(responseCode = "201", description = "생성 성공")
    @ApiResponse(responseCode = "401", description = "인증되지 않음", content = @Content)
    @PostMapping
    public ResponseEntity<ItemResponse> create(
            @AuthenticationPrincipal Long ownerId,
            @RequestBody ItemCreateRequest request) {
        ItemResponse response = itemService.create(ownerId, request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @Operation(
            summary = "아이템 목록 조회",
            description = "현재 사용자가 소유한 아이템 목록을 반환한다."
    )
    @ApiResponse(responseCode = "200", description = "조회 성공")
    @ApiResponse(responseCode = "401", description = "인증되지 않음", content = @Content)
    @GetMapping
    public ResponseEntity<List<ItemResponse>> findAll(@AuthenticationPrincipal Long ownerId) {
        return ResponseEntity.ok(itemService.findAll(ownerId));
    }

    @Operation(
            summary = "아이템 단건 조회",
            description = "아이템 ID 로 단건을 조회한다. 타인 소유이거나 삭제된 아이템은 404 다."
    )
    @ApiResponse(responseCode = "200", description = "조회 성공")
    @ApiResponse(responseCode = "401", description = "인증되지 않음", content = @Content)
    @ApiResponse(responseCode = "404", description = "존재하지 않거나 접근 권한이 없는 아이템", content = @Content)
    @GetMapping("/{id}")
    public ResponseEntity<ItemResponse> findOne(
            @AuthenticationPrincipal Long ownerId,
            @Parameter(description = "아이템 ID", example = "3f9a1c2e-7b4d-4e8a-9c1f-0a2b3c4d5e6f")
            @PathVariable("id") UUID id) {
        return ResponseEntity.ok(itemService.findOne(id, ownerId));
    }

    @Operation(
            summary = "아이템 수정",
            description = "전체 교체 방식이다. name 과 attributes 를 모두 전달해야 한다."
    )
    @ApiResponse(responseCode = "200", description = "수정 성공")
    @ApiResponse(responseCode = "401", description = "인증되지 않음", content = @Content)
    @ApiResponse(responseCode = "404", description = "존재하지 않거나 접근 권한이 없는 아이템", content = @Content)
    @PutMapping("/{id}")
    public ResponseEntity<ItemResponse> update(
            @AuthenticationPrincipal Long ownerId,
            @Parameter(description = "아이템 ID", example = "3f9a1c2e-7b4d-4e8a-9c1f-0a2b3c4d5e6f")
            @PathVariable("id") UUID id,
            @RequestBody ItemUpdateRequest request) {
        return ResponseEntity.ok(itemService.update(id, ownerId, request));
    }

    @Operation(
            summary = "아이템 삭제",
            description = "Soft Delete 로 처리한다. 삭제 후 조회하면 404 다."
    )
    @ApiResponse(responseCode = "204", description = "삭제 성공")
    @ApiResponse(responseCode = "401", description = "인증되지 않음", content = @Content)
    @ApiResponse(responseCode = "404", description = "존재하지 않거나 접근 권한이 없는 아이템", content = @Content)
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(
            @AuthenticationPrincipal Long ownerId,
            @Parameter(description = "아이템 ID", example = "3f9a1c2e-7b4d-4e8a-9c1f-0a2b3c4d5e6f")
            @PathVariable("id") UUID id) {
        itemService.delete(id, ownerId);
        return ResponseEntity.noContent().build();
    }
}
