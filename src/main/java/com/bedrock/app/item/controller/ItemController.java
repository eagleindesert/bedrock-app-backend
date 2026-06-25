package com.bedrock.app.item.controller;

import com.bedrock.app.item.dto.request.ItemCreateRequest;
import com.bedrock.app.item.dto.request.ItemUpdateRequest;
import com.bedrock.app.item.dto.response.ItemResponse;
import com.bedrock.app.item.service.ItemService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/items")
@RequiredArgsConstructor
public class ItemController {

    private final ItemService itemService;

    @PostMapping
    public ResponseEntity<ItemResponse> create(
            @AuthenticationPrincipal Long ownerId,
            @RequestBody ItemCreateRequest request) {
        ItemResponse response = itemService.create(ownerId, request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @GetMapping
    public ResponseEntity<List<ItemResponse>> findAll(@AuthenticationPrincipal Long ownerId) {
        return ResponseEntity.ok(itemService.findAll(ownerId));
    }

    @GetMapping("/{id}")
    public ResponseEntity<ItemResponse> findOne(
            @AuthenticationPrincipal Long ownerId,
            @PathVariable("id") UUID id) {
        return ResponseEntity.ok(itemService.findOne(id, ownerId));
    }

    @PutMapping("/{id}")
    public ResponseEntity<ItemResponse> update(
            @AuthenticationPrincipal Long ownerId,
            @PathVariable("id") UUID id,
            @RequestBody ItemUpdateRequest request) {
        return ResponseEntity.ok(itemService.update(id, ownerId, request));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(
            @AuthenticationPrincipal Long ownerId,
            @PathVariable("id") UUID id) {
        itemService.delete(id, ownerId);
        return ResponseEntity.noContent().build();
    }
}
