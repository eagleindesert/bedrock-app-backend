package com.bedrock.app.debug;

import io.swagger.v3.oas.annotations.Hidden;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

// 디버깅 전용 엔드포인트이므로 OpenAPI 문서에서 제외
@Hidden
@RestController
@RequestMapping("/api/debug")
@RequiredArgsConstructor
public class DebugController {
}
