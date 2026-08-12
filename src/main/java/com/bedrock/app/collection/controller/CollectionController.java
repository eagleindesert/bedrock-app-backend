package com.bedrock.app.collection.controller;

import com.bedrock.app.collection.dto.request.CollectionCreateRequest;
import com.bedrock.app.collection.dto.response.CollectionResponse;
import com.bedrock.app.collection.service.CollectionService;
import com.bedrock.app.common.exception.ErrorResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Tag(
        name = "Collection",
        description = "컬렉션 API. 모든 엔드포인트가 인증을 요구한다. "
                + "생성 시 요청한 사용자가 role=owner 로 자동 등록되며, "
                + "목록 조회는 내가 속한 컬렉션만 반환한다."
)
@RestController
@RequestMapping("/api/v1/collections")
@RequiredArgsConstructor
public class CollectionController {

    private final CollectionService collectionService;

    @Operation(
            summary = "컬렉션 목록 조회",
            description = "내가 속한 컬렉션 목록을 반환한다 (owner 및 초대된 editor/viewer 포함)."
    )
    @ApiResponse(responseCode = "200", description = "조회 성공")
    @ApiResponse(responseCode = "400", description = "존재하지 않는 kind 값",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @ApiResponse(responseCode = "401", description = "인증되지 않음", content = @Content)
    @GetMapping
    public ResponseEntity<List<CollectionResponse>> findAll(
            @AuthenticationPrincipal Long userId,
            @Parameter(description = "컬렉션 종류 필터 (생략 시 전체)", example = "calendar")
            @RequestParam(required = false) String kind,
            @Parameter(description = "내 컬렉션만 조회 명시 파라미터 (현재 필터링에 사용되지 않음)", example = "me")
            @RequestParam(required = false) String owner) {
        return ResponseEntity.ok(collectionService.findAll(userId, kind));
    }

    @Operation(
            summary = "컬렉션 생성",
            description = "컬렉션을 생성하고 요청한 사용자를 owner 로 등록한다."
    )
    @ApiResponse(responseCode = "201", description = "생성 성공 (role=owner 자동 지정)")
    @ApiResponse(responseCode = "400", description = "존재하지 않는 kind 값 또는 요청 값 검증 실패",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @ApiResponse(responseCode = "401", description = "인증되지 않음", content = @Content)
    @PostMapping
    public ResponseEntity<CollectionResponse> create(
            @AuthenticationPrincipal Long userId,
            @RequestBody CollectionCreateRequest request) {
        CollectionResponse response = collectionService.create(userId, request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }
}
