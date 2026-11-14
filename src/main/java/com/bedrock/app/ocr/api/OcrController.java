package com.bedrock.app.ocr.api;

import com.bedrock.app.ai.service.AiWorkspaceAccess;
import com.bedrock.app.ai.service.InferenceLimits;
import com.bedrock.app.common.auth.CurrentUser;
import com.bedrock.app.common.auth.SessionPrincipal;
import com.bedrock.app.ocr.dto.OcrDtos;
import com.bedrock.app.ocr.service.MemoryCapture;
import com.bedrock.app.ocr.service.TimetableRecognition;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;

@RestController("ocrController")
@RequestMapping("/api/v1/ocr")
@Tag(name = "시간표 캡처", description = "메모리에서만 처리하는 서버 인식·후보 반환")
public class OcrController {
    private final AiWorkspaceAccess access;
    private final InferenceLimits limits;
    private final TimetableRecognition recognition;
    public OcrController(AiWorkspaceAccess access, InferenceLimits limits, TimetableRecognition recognition) {
        this.access = access; this.limits = limits; this.recognition = recognition;
    }

    @PostMapping(value = "/timetables", consumes = "multipart/form-data")
    @Operation(summary = "시간표 캡처 인식", description = "capture 하나, PNG/JPEG/WebP/HEIC, 최대 10MB. OCR 동의 확인 후 메모리에서 처리하고 폐기한다. 후보는 아이템으로 저장하지 않는다.")
    public OcrDtos.Recognition recognize(@CurrentUser SessionPrincipal user, HttpServletRequest request) {
        access.requireOcrConsent(user.userId());
        try (var image = MemoryCapture.read(request)) {
            var result = limits.execute(user.userId(), true, () -> recognition.recognize(image));
            access.requireOcrConsent(user.userId());
            return result;
        }
    }
}
