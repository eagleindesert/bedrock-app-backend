package com.bedrock.app.auth.api;

import io.swagger.v3.oas.annotations.Hidden;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

import java.io.IOException;

@Controller("notMePageController")
@Hidden
public class NotMePageController {
    @GetMapping("/api/v1/auth/not-me")
    public void page(HttpServletResponse response) throws IOException {
        headers(response, "text/html");
        response.getWriter().write("""
                <!doctype html><html lang="ko"><head><meta charset="utf-8">
                <meta name="viewport" content="width=device-width,initial-scale=1">
                <title>베드락 · 로그인 연결 확인</title></head><body><main>
                <h1>직접 연결한 로그인이 아닌가요?</h1>
                <p id="status" role="status">링크를 확인하고 있습니다.</p>
                <p>연결을 해제하면 모든 기기의 로그인이 종료됩니다. 계정의 기록은 유지됩니다.</p>
                <button id="revoke" type="button" disabled>이 연결을 해제하고 모두 로그아웃</button>
                <p>직접 연결했다면 이 창을 닫으세요.</p>
                </main><script src="/api/v1/auth/not-me.js" defer></script></body></html>
                """);
    }

    @GetMapping("/api/v1/auth/not-me.js")
    public void script(HttpServletResponse response) throws IOException {
        headers(response, "text/javascript");
        response.getWriter().write("""
                'use strict';
                (async () => {
                  let token = location.hash.slice(1);
                  history.replaceState(null, '', location.pathname);
                  const status = document.getElementById('status');
                  const button = document.getElementById('revoke');
                  let csrf;
                  async function post(path, confirmed) {
                    const reply = await fetch('/api/v1/auth/not-me/' + path, {
                      method: 'POST', credentials: 'same-origin', cache: 'no-store',
                      headers: {'Content-Type': 'application/json', [csrf.headerName]: csrf.token},
                      body: JSON.stringify({token, confirmed})
                    });
                    const result = await reply.json();
                    if (!reply.ok || !result.success) throw new Error('request failed');
                    return result.data;
                  }
                  try {
                    if (!/^[A-Za-z0-9_-]{43}$/.test(token)) throw new Error('invalid link');
                    const reply = await fetch('/api/v1/auth/csrf', {credentials: 'same-origin', cache: 'no-store'});
                    const result = await reply.json();
                    if (!reply.ok || !result.success) throw new Error('session unavailable');
                    csrf = result.data;
                    const info = await post('inspect', false);
                    status.textContent = info.provider + ' 연결을 해제할 수 있습니다. 만료: ' + new Date(info.expiresAt).toLocaleString();
                    button.disabled = false;
                  } catch (_) {
                    status.textContent = '링크가 만료되었거나 지금 확인할 수 없습니다. 잠시 후 다시 열어 주세요.';
                  }
                  button.addEventListener('click', async () => {
                    button.disabled = true;
                    try {
                      await post('revoke', true);
                      token = '';
                      status.textContent = '연결을 해제하고 모든 로그인을 종료했습니다.';
                    } catch (_) {
                      status.textContent = '이미 해제되었거나 처리할 수 없습니다. 링크를 다시 확인해 주세요.';
                    }
                  });
                })();
                """);
    }

    private void headers(HttpServletResponse response, String type) {
        response.setContentType(type);
        response.setCharacterEncoding("UTF-8");
        response.setHeader("Cache-Control", "no-store");
        response.setHeader("Referrer-Policy", "no-referrer");
        response.setHeader("Content-Security-Policy", "default-src 'none'; script-src 'self'; connect-src 'self'; base-uri 'none'; frame-ancestors 'none'; form-action 'none'");
        response.setHeader("X-Content-Type-Options", "nosniff");
    }
}
