package com.bedrock.app.auth.security;

import java.io.ByteArrayOutputStream;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.Arrays;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** 응답 전체에 요청 시간 상한을 적용하고 한도를 넘는 본문은 수신 중 중단한다. */
public final class SafeHttp {
    private SafeHttp() {}

    public static HttpResponse<byte[]> send(HttpClient client, HttpRequest request, int maximumBytes)
            throws java.io.IOException, InterruptedException {
        if (maximumBytes < 1) throw new IllegalArgumentException("외부 응답 크기 제한이 필요합니다.");
        long timeout = request.timeout().orElseThrow(() -> new IllegalArgumentException("외부 요청 시간 제한이 필요합니다.")).toNanos();
        long started = System.nanoTime();
        var body = new LimitedBody(maximumBytes);
        CompletableFuture<HttpResponse<byte[]>> pending = null;
        try {
            // Java 21의 request.timeout은 헤더 도착까지만 적용된다. 본문 완료까지 별도로 기다린다.
            pending = client.sendAsync(request, response -> body);
            long remaining = timeout - (System.nanoTime() - started);
            if (remaining <= 0) throw new TimeoutException();
            return pending.get(remaining, TimeUnit.NANOSECONDS);
        } catch (TimeoutException exception) {
            throw new HttpTimeoutException("외부 응답 시간 초과");
        } catch (ExecutionException | RuntimeException exception) {
            // HTTP 예외의 URL·쿼리·본문을 상위 예외에 포함하지 않는다.
            throw new java.io.IOException("외부 응답 수신 실패");
        } finally {
            if (pending != null && !pending.isDone()) pending.cancel(true);
            body.close();
        }
    }

    private static final class LimitedBody implements HttpResponse.BodySubscriber<byte[]> {
        private final CompletableFuture<byte[]> result = new CompletableFuture<>();
        private final WipeableBuffer output = new WipeableBuffer();
        private final int limit;
        private Flow.Subscription subscription;
        private LimitedBody(int limit) { this.limit = limit; }
        @Override public CompletionStage<byte[]> getBody() { return result; }
        @Override public synchronized void onSubscribe(Flow.Subscription value) {
            if (subscription != null || result.isDone()) { value.cancel(); return; }
            subscription = value;
            value.request(1);
        }
        @Override public synchronized void onNext(List<ByteBuffer> buffers) {
            if (result.isDone()) return;
            for (var buffer : buffers) {
                if (buffer.remaining() > limit - output.size()) {
                    subscription.cancel();
                    output.wipe();
                    result.completeExceptionally(new java.io.IOException("외부 응답 크기 초과"));
                    return;
                }
                byte[] bytes = new byte[buffer.remaining()];
                buffer.get(bytes);
                output.writeBytes(bytes);
                java.util.Arrays.fill(bytes, (byte) 0);
            }
            subscription.request(1);
        }
        @Override public synchronized void onError(Throwable error) {
            output.wipe();
            result.completeExceptionally(new java.io.IOException("외부 응답 수신 실패"));
        }
        @Override public synchronized void onComplete() {
            if (result.isDone()) return;
            byte[] bytes = output.toByteArray();
            output.wipe();
            result.complete(bytes);
        }
        private synchronized void close() {
            if (subscription != null && !result.isDone()) subscription.cancel();
            output.wipe();
            result.cancel(false);
        }
    }

    private static final class WipeableBuffer extends ByteArrayOutputStream {
        private void wipe() { Arrays.fill(buf, (byte) 0); reset(); }
    }
}
