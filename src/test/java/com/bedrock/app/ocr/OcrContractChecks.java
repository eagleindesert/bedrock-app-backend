package com.bedrock.app.ocr;

import com.bedrock.app.common.error.BusinessException;
import com.bedrock.app.ocr.service.MemoryCapture;
import com.bedrock.app.ocr.service.TimetableRecognition;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import tools.jackson.databind.json.JsonMapper;

import java.io.ByteArrayInputStream;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

public final class OcrContractChecks {
    public static void main(String[] args) { inMemoryUpload(); strictMultipart(); recognitionValidation(); System.out.println("OCR: 3 contract groups passed"); }
    static void inMemoryUpload() {
        byte[] png = {(byte) 137, 80, 78, 71, 13, 10, 26, 10, 0, 1};
        var capture = MemoryCapture.read(request(body("image/png", png), "multipart/form-data; boundary=test-boundary"));
        require(Arrays.equals(png, capture.bytes()), "이미지 바이트 보존");
        byte[] retained = capture.bytes(); capture.close();
        for (byte value : retained) require(value == 0, "처리 뒤 메모리 지우기");
    }
    static void strictMultipart() {
        byte[] png = {(byte) 137, 80, 78, 71, 13, 10, 26, 10};
        reject(() -> MemoryCapture.read(request(body("image/jpeg", png), "multipart/form-data; boundary=test-boundary")));
        reject(() -> MemoryCapture.read(request(body("image/png", png), "multipart/form-data; boundary=other-boundary")));
        byte[] oversized = new byte[MemoryCapture.MAX_BYTES + 8193];
        reject(() -> MemoryCapture.read(request(oversized, "multipart/form-data; boundary=test-boundary")));
        reject(() -> MemoryCapture.detect("not-an-image".getBytes(StandardCharsets.US_ASCII)));
        byte[] heic = {0, 0, 0, 24, 'f', 't', 'y', 'p', 'h', 'e', 'i', 'c', 0, 0, 0, 0, 'm', 'i', 'f', '1', 'h', 'e', 'i', 'c'};
        require("image/heic".equals(MemoryCapture.detect(heic)), "HEIC 컨테이너 인식");
    }
    static void recognitionValidation() {
        var mapper = JsonMapper.builder().build();
        var service = new TimetableRecognition(null, null, null, mapper, 20000);
        var valid = mapper.readTree("""
                {"items":[{"candidateId":"one","name":null,"location":null,"slots":[{"dayOfWeek":null,"startTime":null,"endTime":null}]}],"warnings":[]}
                """);
        require(service.validate(valid, "test").items().getFirst().slots().getFirst().dayOfWeek() == null, "미인식 값은 null 유지");
        reject(() -> service.validate(mapper.readTree("""
                {"items":[{"candidateId":"one","name":"test","location":null,"slots":[{"dayOfWeek":8,"startTime":"10:00:00","endTime":"09:00:00"}]}],"warnings":[]}
                """), "test"));
        reject(() -> service.validate(mapper.readTree("""
                {"items":[{"candidateId":"one","name":"test","location":null,"slots":[{"dayOfWeek":1,"startTime":"09:00:01","endTime":"10:00:00"}]}],"warnings":[]}
                """), "test"));
    }
    private static byte[] body(String type, byte[] image) {
        var bytes = new java.io.ByteArrayOutputStream();
        bytes.writeBytes(("--test-boundary\r\nContent-Disposition: form-data; name=\"capture\"; filename=\"../../private.png\"\r\nContent-Type: " + type + "\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
        bytes.writeBytes(image); bytes.writeBytes("\r\n--test-boundary--\r\n".getBytes(StandardCharsets.US_ASCII)); return bytes.toByteArray();
    }
    private static HttpServletRequest request(byte[] body, String contentType) {
        return (HttpServletRequest) Proxy.newProxyInstance(OcrContractChecks.class.getClassLoader(), new Class<?>[]{HttpServletRequest.class}, (proxy, method, args) -> switch (method.getName()) {
            case "getContentType" -> contentType;
            case "getContentLengthLong" -> (long) body.length;
            case "getInputStream" -> new ServletInputStream() {
                private final ByteArrayInputStream input = new ByteArrayInputStream(body);
                @Override public int read() { return input.read(); }
                @Override public boolean isFinished() { return input.available() == 0; }
                @Override public boolean isReady() { return true; }
                @Override public void setReadListener(ReadListener listener) { throw new UnsupportedOperationException(); }
            };
            case "getParts", "getPart", "getParameter", "getParameterMap" -> throw new AssertionError("Servlet multipart 파서/임시 파일 경로를 호출했습니다.");
            default -> throw new UnsupportedOperationException(method.getName());
        });
    }
    private static void reject(Runnable task) { try { task.run(); throw new AssertionError("잘못된 이미지/결과를 받아들였습니다."); } catch (BusinessException expected) {} }
    private static void require(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
