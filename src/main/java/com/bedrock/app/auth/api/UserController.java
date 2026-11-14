package com.bedrock.app.auth.api;

import com.bedrock.app.auth.dto.AuthDtos;
import com.bedrock.app.auth.persistence.AuthStore;
import com.bedrock.app.auth.service.AccountLifecycle;
import com.bedrock.app.auth.service.AuthSessions;
import com.bedrock.app.common.auth.CurrentUser;
import com.bedrock.app.common.auth.SessionPrincipal;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

@RestController("be1UserController")
@RequestMapping("/api/v1/users/me")
@Tag(name = "내 계정", description = "프로필과 탈퇴")
public class UserController {
    private final AuthStore store;
    private final AccountLifecycle lifecycle;
    private final AuthSessions sessions;
    public UserController(AuthStore store, AccountLifecycle lifecycle, AuthSessions sessions) { this.store = store; this.lifecycle = lifecycle; this.sessions = sessions; }

    @PatchMapping
    @Operation(summary = "프로필 이름 수정", description = "공백뿐인 이름을 거절한다. 이메일은 변경하지 않는다.")
    public AuthDtos.UserDto rename(@CurrentUser SessionPrincipal user, @Valid @RequestBody AuthDtos.ProfileRequest input) {
        return store.locked(user.userId(), () -> store.rename(user.userId(), input.name()));
    }

    @DeleteMapping
    @Operation(summary = "계정 탈퇴", description = "30일 유예와 삭제 예정 시각을 저장하고 모든 세션을 종료한다. Apple 토큰 해지는 장애 시 내부 작업으로 재시도한다.")
    public AuthDtos.AccountDeletion delete(@CurrentUser SessionPrincipal user, HttpServletRequest request) {
        var result = lifecycle.delete(user.userId());
        sessions.signOut(request);
        return result;
    }
}
