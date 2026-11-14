package com.bedrock.app.ai.api;

import com.bedrock.app.ai.dto.AiDtos;
import com.bedrock.app.ai.service.AiRequestService;
import com.bedrock.app.ai.service.AiValidation;
import com.bedrock.app.common.auth.CurrentUser;
import com.bedrock.app.common.auth.SessionPrincipal;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.*;
import tools.jackson.databind.JsonNode;

import java.util.UUID;

@RestController("aiController")
@RequestMapping("/api/v1/ai")
@Tag(name = "문장 해석", description = "동의·출력 구조·후보 범위를 검증하는 AI 중계와 되묻기")
public class AiController {
    private final AiRequestService service;
    private final AiValidation validation;
    public AiController(AiRequestService service, AiValidation validation) { this.service = service; this.validation = validation; }

    @PostMapping("/interpretations")
    @Operation(summary = "문장 해석", description = "동의와 후보 소유권을 확인한 최소 맥락만 보낸다. READY는 저장 완료가 아니며 FE 검증 뒤 묶음 API로 저장한다.")
    public AiDtos.Interpretation interpret(@CurrentUser SessionPrincipal user, @RequestBody JsonNode body) {
        return service.interpret(user.userId(), validation.readRequest(body, AiDtos.InterpretationRequest.class));
    }

    @GetMapping("/requests/{requestId}")
    @Operation(summary = "해석 요청·선저장 결과 조회", description = "암호화된 원래 맥락과 질문·답·버전을 반환한다. 다른 기기에서 저장했으면 기존 묶음을 가리킨다.")
    public AiDtos.RequestView get(@CurrentUser SessionPrincipal user, @PathVariable UUID requestId) { return service.get(user.userId(), requestId); }

    @PostMapping("/requests/{requestId}/answers")
    @Operation(summary = "되묻기에 이어 답하기", description = "최초 기준 시각·시간대와 맥락을 유지한다. 답 재전송은 answerId로 중복 적용을 막고 다른 기기의 변경은 버전으로 검사한다.")
    public AiDtos.Interpretation answer(@CurrentUser SessionPrincipal user, @PathVariable UUID requestId, @RequestBody JsonNode body) {
        return service.answer(user.userId(), requestId, validation.readRequest(body, AiDtos.AnswerRequest.class));
    }
}
