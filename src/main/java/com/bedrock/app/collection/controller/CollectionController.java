package com.bedrock.app.collection.controller;

import com.bedrock.app.collection.dto.request.CollectionCreateRequest;
import com.bedrock.app.collection.dto.response.CollectionResponse;
import com.bedrock.app.collection.service.CollectionService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/collections")
@RequiredArgsConstructor
public class CollectionController {

    private final CollectionService collectionService;

    @GetMapping
    public ResponseEntity<List<CollectionResponse>> findAll(
            @AuthenticationPrincipal Long userId,
            @RequestParam(required = false) String kind,
            @RequestParam(required = false) String owner) {
        return ResponseEntity.ok(collectionService.findAll(userId, kind));
    }

    @PostMapping
    public ResponseEntity<CollectionResponse> create(
            @AuthenticationPrincipal Long userId,
            @RequestBody CollectionCreateRequest request) {
        CollectionResponse response = collectionService.create(userId, request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }
}
