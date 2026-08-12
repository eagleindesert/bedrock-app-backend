package com.bedrock.app.action.dto;

import com.bedrock.app.action.domain.type.BlockCode;
import com.fasterxml.jackson.annotation.JsonAlias;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class ActionRequests {

    private ActionRequests() {
    }

    @Schema(name = "ActionBlockRequest", description = "액션에 포함할 Block 구성")
    public record Block(
            @Schema(description = "Block 코드", example = "TODO")
            @NotNull BlockCode blockCode,

            @Schema(description = "UI 표시 순서 (실행 순서가 아님)", example = "0")
            @Min(0) Integer displayOrder
    ) {
    }

    @Schema(name = "ActionCreateRequest", description = "액션 생성 요청")
    public record Create(
            @Schema(description = "액션 이름", example = "투두 만들기")
            @NotBlank @Size(max = 100) String name,

            @Schema(description = "액션 설명", example = "새 할 일을 만드는 입력 정의")
            @Size(max = 1000) String description,

            @Schema(description = "활성 여부 (기본 true)", example = "true")
            Boolean enabled,

            @Schema(description = "프리셋 여부. true 생성은 관리자만 가능하다.", example = "false")
            Boolean preset,

            @Schema(description = "Block 구성 목록 (최소 1개)")
            @NotEmpty List<@Valid Block> blocks
    ) {
    }

    @Schema(name = "ActionUpdateRequest",
            description = "액션 부분 수정 요청. 전달한 필드만 수정되며, blocks 를 전달하면 구성 전체가 교체된다.")
    public record Update(
            @Schema(description = "액션 이름", example = "투두 만들기 (수정)")
            @Size(min = 1, max = 100) String name,

            @Schema(description = "액션 설명", example = "설명 수정")
            @Size(max = 1000) String description,

            @Schema(description = "활성 여부", example = "false")
            Boolean enabled,

            @Schema(description = "프리셋 여부 (관리자 전용)", example = "false")
            Boolean preset,

            @Schema(description = "교체할 Block 구성 목록")
            List<@Valid Block> blocks
    ) {
    }

    @Schema(name = "ActionExecuteRequest", description = "액션 실행 요청")
    public record Execute(
            @Schema(description = "Item 을 생성할 대상 컬렉션 ID",
                    example = "835c78c3-8d10-4f26-a40b-9c8cf0599886")
            @NotNull UUID targetCollectionId,

            @Schema(description = "트리거 문맥으로 입력 화면을 미리 채우는 값 (prefill 로도 전달 가능). "
                    + "같은 필드가 input 에 있으면 input 이 우선한다.",
                    example = "{\"title\": \"캡스톤 발표자료 준비\"}")
            @JsonAlias("prefill") Map<String, Object> triggerPayload,

            @Schema(description = "사용자가 최종 확정한 입력 값",
                    example = "{\"title\": \"캡스톤 발표자료 준비\", \"dueDate\": \"2026-04-28\", "
                            + "\"priority\": \"P1\", \"tags\": [\"팀플\"], \"completed\": false}")
            Map<String, Object> input
    ) {
    }
}
