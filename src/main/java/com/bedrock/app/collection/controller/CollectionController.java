package com.bedrock.app.collection.controller;

import com.bedrock.app.collection.dto.request.CollectionCreateRequest;
import com.bedrock.app.collection.dto.request.CollectionUpdateRequest;
import com.bedrock.app.collection.dto.request.CollectionUserAddRequest;
import com.bedrock.app.collection.dto.response.CollectionResponse;
import com.bedrock.app.collection.dto.response.CollectionUserResponse;
import com.bedrock.app.collection.service.CollectionService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/collections")
@RequiredArgsConstructor
public class CollectionController {

    private final CollectionService collectionService;

    // #26
    @GetMapping
    public ResponseEntity<List<CollectionResponse>> findAll(
            @AuthenticationPrincipal Long userId,
            @RequestParam(required = false) String kind,
            @RequestParam(required = false) String owner) {
        return ResponseEntity.ok(collectionService.findAll(userId, kind));
    }

    // #27
    @PostMapping
    public ResponseEntity<CollectionResponse> create(
            @AuthenticationPrincipal Long userId,
            @RequestBody CollectionCreateRequest request) {
        CollectionResponse response = collectionService.create(userId, request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    // #28
    @GetMapping("/{id}")
    public ResponseEntity<CollectionResponse> findOne(
            @AuthenticationPrincipal Long userId,
            @PathVariable("id") UUID id) {
        return ResponseEntity.ok(collectionService.findOne(userId, id));
    }

    // #29
    @PatchMapping("/{id}")
    public ResponseEntity<CollectionResponse> update(
            @AuthenticationPrincipal Long userId,
            @PathVariable("id") UUID id,
            @RequestBody CollectionUpdateRequest request) {
        return ResponseEntity.ok(collectionService.update(userId, id, request));
    }

    // #30
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(
            @AuthenticationPrincipal Long userId,
            @PathVariable("id") UUID id) {
        collectionService.delete(userId, id);
        return ResponseEntity.noContent().build();
    }

    // #31
    @GetMapping("/{id}/users")
    public ResponseEntity<List<CollectionUserResponse>> findMembers(
            @AuthenticationPrincipal Long userId,
            @PathVariable("id") UUID id) {
        return ResponseEntity.ok(collectionService.findMembers(userId, id));
    }

    // #32
    @PostMapping("/{id}/users")
    public ResponseEntity<CollectionUserResponse> addMember(
            @AuthenticationPrincipal Long userId,
            @PathVariable("id") UUID id,
            @RequestBody CollectionUserAddRequest request) {
        CollectionUserResponse response = collectionService.addMember(userId, id, request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    // #33
    @DeleteMapping("/{id}/users/{userId}")
    public ResponseEntity<Void> removeMember(
            @AuthenticationPrincipal Long requesterId,
            @PathVariable("id") UUID id,
            @PathVariable("userId") Long userId) {
        collectionService.removeMember(requesterId, id, userId);
        return ResponseEntity.noContent().build();
    }
}
