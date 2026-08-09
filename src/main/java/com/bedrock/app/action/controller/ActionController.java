package com.bedrock.app.action.controller;

import com.bedrock.app.action.application.ActionService;
import com.bedrock.app.action.dto.ActionRequests;
import com.bedrock.app.action.dto.ActionResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/actions")
@RequiredArgsConstructor
public class ActionController {

    private final ActionService actionService;

    @GetMapping
    public ResponseEntity<List<ActionResponse>> findAll(
            @AuthenticationPrincipal Long ownerId,
            @RequestParam(defaultValue = "available") String scope
    ) {
        return ResponseEntity.ok(
                actionService.findAll(ownerId, scope)
        );
    }

    @PostMapping
    public ResponseEntity<ActionResponse> create(
            @AuthenticationPrincipal Long ownerId,
            Authentication authentication,
            @Valid @RequestBody ActionRequests.Create request
    ) {
        ActionResponse response =
                actionService.create(ownerId, isAdmin(authentication), request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @GetMapping("/{actionId}")
    public ResponseEntity<ActionResponse> findOne(
            @AuthenticationPrincipal Long ownerId,
            @PathVariable UUID actionId
    ) {
        return ResponseEntity.ok(actionService.findOne(actionId, ownerId));
    }

    @PatchMapping("/{actionId}")
    public ResponseEntity<ActionResponse> update(
            @AuthenticationPrincipal Long ownerId,
            Authentication authentication,
            @PathVariable UUID actionId,
            @Valid @RequestBody ActionRequests.Update request
    ) {
        return ResponseEntity.ok(
                actionService.update(
                        actionId,
                        ownerId,
                        isAdmin(authentication),
                        request
                )
        );
    }

    @DeleteMapping("/{actionId}")
    public ResponseEntity<Void> delete(
            @AuthenticationPrincipal Long ownerId,
            Authentication authentication,
            @PathVariable UUID actionId
    ) {
        actionService.delete(actionId, ownerId, isAdmin(authentication));
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{actionId}/execute")
    public ResponseEntity<ActionResponse.Execution> execute(
            @AuthenticationPrincipal Long ownerId,
            @PathVariable UUID actionId,
            @Valid @RequestBody ActionRequests.Execute request
    ) {
        return ResponseEntity.ok(
                actionService.execute(actionId, ownerId, request)
        );
    }

    private boolean isAdmin(Authentication authentication) {
        if (authentication == null) {
            return false;
        }
        for (GrantedAuthority authority : authentication.getAuthorities()) {
            if ("ROLE_ADMIN".equals(authority.getAuthority())) {
                return true;
            }
        }
        return false;
    }
}
