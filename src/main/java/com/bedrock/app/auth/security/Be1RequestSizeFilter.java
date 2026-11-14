package com.bedrock.app.auth.security;

import com.bedrock.app.common.error.ApiErrorWriter;
import com.bedrock.app.common.error.Be1AuthAiErrorCode;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/** BE1 JSON만 제한한다. 다른 담당의 업로드나 쓰기 계약을 변경하지 않는다. */
public final class Be1RequestSizeFilter extends OncePerRequestFilter {
    private static final long MAX_JSON_BYTES = 512 * 1024;
    private final ApiErrorWriter errors;
    public Be1RequestSizeFilter(ApiErrorWriter errors) { this.errors = errors; }
    @Override protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        return !(path.startsWith("/api/v1/auth/") || path.equals("/api/v1/users/me") || path.startsWith("/api/v1/ai/")
                || path.equals("/api/v1/conversations") || path.startsWith("/api/v1/conversations/"));
    }
    @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        if (request.getContentLengthLong() > MAX_JSON_BYTES) {
            errors.write(response, Be1AuthAiErrorCode.WRITE_REJECTED_VALIDATION);
            return;
        }
        chain.doFilter(new HttpServletRequestWrapper(request) {
            private ServletInputStream limited;
            @Override public ServletInputStream getInputStream() throws IOException {
                if (limited != null) return limited;
                var original = super.getInputStream();
                limited = new ServletInputStream() {
                    private long count;
                    @Override public int read() throws IOException {
                        int value = original.read();
                        if (value != -1 && ++count > MAX_JSON_BYTES) throw new IOException("요청 크기 초과");
                        return value;
                    }
                    @Override public int read(byte[] bytes, int offset, int length) throws IOException {
                        int received = original.read(bytes, offset, (int) Math.min(length, Math.max(1, MAX_JSON_BYTES - count + 1)));
                        if (received > 0 && (count += received) > MAX_JSON_BYTES) throw new IOException("요청 크기 초과");
                        return received;
                    }
                    @Override public boolean isFinished() { return original.isFinished(); }
                    @Override public boolean isReady() { return original.isReady(); }
                    @Override public void setReadListener(ReadListener listener) { original.setReadListener(listener); }
                    @Override public void close() throws IOException { original.close(); }
                };
                return limited;
            }
            @Override public java.io.BufferedReader getReader() throws IOException {
                return new java.io.BufferedReader(new java.io.InputStreamReader(getInputStream(), java.nio.charset.StandardCharsets.UTF_8));
            }
        }, response);
    }
}
