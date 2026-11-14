package com.bedrock.app.auth;

import com.bedrock.app.auth.security.SafeHttp;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSession;
import java.io.IOException;
import java.net.Authenticator;
import java.net.CookieHandler;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/** 실제 BodySubscriber를 가짜 HTTP 전송에 붙인다. 소켓 없이 본문 지연·분할·취소를 검증한다. */
public final class SafeHttpContractChecks {
    public static void main(String[] args) throws Exception {
        receivesBoundedChunks(); rejectsOverflow(); cancelsStalledBody(); cancelsInterruptedCall(); removesTransportDetails();
        System.out.println("HTTP: 5 contract groups passed");
    }

    static void receivesBoundedChunks() throws Exception {
        var client = new Transport(Mode.COMPLETE);
        var result = SafeHttp.send(client, request(Duration.ofSeconds(1)), 4);
        require(java.util.Arrays.equals(result.body(), new byte[]{1, 2, 3, 4}), "분할 응답을 한도 안에서 조합");
        require(!client.cancelled, "정상 완료의 불필요한 전송 취소 없음");
        try {
            SafeHttp.send(client, HttpRequest.newBuilder(URI.create("https://example.invalid")).GET().build(), 4);
            throw new AssertionError("시간 상한 없는 요청을 허용했습니다.");
        } catch (IllegalArgumentException expected) {}
    }

    static void rejectsOverflow() throws Exception {
        var client = new Transport(Mode.COMPLETE);
        try { SafeHttp.send(client, request(Duration.ofSeconds(1)), 3); throw new AssertionError("한도 초과 본문을 허용했습니다."); }
        catch (IOException expected) { require(client.cancelled, "추가 바이트 수신 취소"); }
    }

    static void cancelsStalledBody() throws Exception {
        var client = new Transport(Mode.STALL);
        try { SafeHttp.send(client, request(Duration.ofMillis(50)), 4); throw new AssertionError("끝나지 않은 본문을 성공 처리했습니다."); }
        catch (HttpTimeoutException expected) {
            require(client.pending.isCancelled() && client.cancelled, "헤더 뒤 정지한 본문과 요청 모두 취소");
        }
    }

    static void cancelsInterruptedCall() throws Exception {
        var client = new Transport(Mode.STALL);
        var failure = new AtomicReference<Throwable>();
        var thread = new Thread(() -> {
            try { SafeHttp.send(client, request(Duration.ofSeconds(10)), 4); }
            catch (Throwable exception) { failure.set(exception); }
        });
        thread.start();
        try {
            require(client.started.await(2, TimeUnit.SECONDS), "본문 수신 시작");
            thread.interrupt();
            thread.join(2000);
            require(!thread.isAlive() && failure.get() instanceof InterruptedException, "호출자 취소 즉시 전파");
            require(client.pending.isCancelled() && client.cancelled, "중단된 HTTP 전송 정리");
        } finally { thread.interrupt(); }
    }

    static void removesTransportDetails() throws Exception {
        var client = new Transport(Mode.FAIL);
        try { SafeHttp.send(client, request(Duration.ofSeconds(1)), 4); throw new AssertionError("실패한 본문을 허용했습니다."); }
        catch (IOException expected) {
            require(expected.getCause() == null && !expected.getMessage().contains("secret"), "외부 오류의 본문·URL 미전파");
        }
    }

    private static HttpRequest request(Duration timeout) {
        return HttpRequest.newBuilder(URI.create("https://example.invalid/private?secret=value")).timeout(timeout).GET().build();
    }
    private static void require(boolean value, String message) { if (!value) throw new AssertionError(message); }
    private enum Mode { COMPLETE, STALL, FAIL }

    private static final class Transport extends HttpClient {
        private final Mode mode;
        private final CountDownLatch started = new CountDownLatch(1);
        private volatile CompletableFuture<?> pending;
        private volatile boolean cancelled;
        private Transport(Mode mode) { this.mode = mode; }

        @Override public <T> CompletableFuture<HttpResponse<T>> sendAsync(HttpRequest request, HttpResponse.BodyHandler<T> handler) {
            var future = new CompletableFuture<HttpResponse<T>>();
            pending = future;
            var body = handler.apply(new HttpResponse.ResponseInfo() {
                public int statusCode() { return 200; }
                public HttpHeaders headers() { return HttpHeaders.of(Map.of(), (a, b) -> true); }
                public Version version() { return Version.HTTP_1_1; }
            });
            body.getBody().whenComplete((value, error) -> {
                if (error != null) future.completeExceptionally(error);
                else future.complete(new Response<>(request, value));
            });
            body.onSubscribe(new Flow.Subscription() {
                public void request(long count) {}
                public void cancel() { cancelled = true; }
            });
            body.onNext(List.of(ByteBuffer.wrap(new byte[]{1, 2})));
            if (mode == Mode.COMPLETE) {
                body.onNext(List.of(ByteBuffer.wrap(new byte[]{3, 4})));
                if (!cancelled) body.onComplete();
            } else if (mode == Mode.FAIL) body.onError(new IOException("secret response body and query"));
            started.countDown();
            return future;
        }
        @Override public <T> HttpResponse<T> send(HttpRequest request, HttpResponse.BodyHandler<T> handler) { throw new AssertionError("본문을 기다리는 비동기 전송이 필요합니다."); }
        @Override public <T> CompletableFuture<HttpResponse<T>> sendAsync(HttpRequest request, HttpResponse.BodyHandler<T> handler, HttpResponse.PushPromiseHandler<T> push) { throw new UnsupportedOperationException(); }
        public Optional<CookieHandler> cookieHandler() { return Optional.empty(); }
        public Optional<Duration> connectTimeout() { return Optional.empty(); }
        public Redirect followRedirects() { return Redirect.NEVER; }
        public Optional<ProxySelector> proxy() { return Optional.empty(); }
        public SSLContext sslContext() { throw new UnsupportedOperationException(); }
        public SSLParameters sslParameters() { return new SSLParameters(); }
        public Optional<Authenticator> authenticator() { return Optional.empty(); }
        public Version version() { return Version.HTTP_1_1; }
        public Optional<Executor> executor() { return Optional.empty(); }
    }

    private record Response<T>(HttpRequest request, T body) implements HttpResponse<T> {
        public int statusCode() { return 200; }
        public Optional<HttpResponse<T>> previousResponse() { return Optional.empty(); }
        public HttpHeaders headers() { return HttpHeaders.of(Map.of(), (a, b) -> true); }
        public Optional<SSLSession> sslSession() { return Optional.empty(); }
        public URI uri() { return request.uri(); }
        public HttpClient.Version version() { return HttpClient.Version.HTTP_1_1; }
    }
}
