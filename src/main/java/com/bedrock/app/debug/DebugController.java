package com.bedrock.app.debug;

import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/debug")
@RequiredArgsConstructor
public class DebugController {

    private final DebugService debugService;

    @PostMapping
    public ResponseEntity<DebugEntity> testDbConnection(@RequestParam("message") String message) {
        DebugEntity saved = debugService.saveMessage(message);
        return ResponseEntity.ok(saved);
    }

    @GetMapping
    public ResponseEntity<List<DebugEntity>> getAllMessages() {
        return ResponseEntity.ok(debugService.getAllMessages());
    }
}
