package com.bedrock.app.ocr.service;

import com.bedrock.app.common.error.Be1AuthAiErrorCode;
import com.bedrock.app.common.error.BusinessException;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.MediaType;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Locale;
import java.util.Map;

/** Servlet Part/임시 파일을 쓰지 않는 단일 capture multipart 파서. */
public final class MemoryCapture implements AutoCloseable {
    public static final int MAX_BYTES = 10 * 1024 * 1024;
    private final byte[] bytes;
    private final String mediaType;
    private MemoryCapture(byte[] bytes, String mediaType) { this.bytes = bytes; this.mediaType = mediaType; }

    public static MemoryCapture read(HttpServletRequest request) {
        byte[] body = null;
        byte[] capture = null;
        try {
            MediaType type = MediaType.parseMediaType(request.getContentType());
            String boundary = type.getParameter("boundary");
            if (!"multipart".equals(type.getType()) || !"form-data".equals(type.getSubtype()) || boundary == null) throw invalid();
            if (boundary.startsWith("\"") && boundary.endsWith("\"")) boundary = boundary.substring(1, boundary.length() - 1);
            if (!boundary.matches("[A-Za-z0-9'()+_,./:=?-]{1,70}")) throw invalid();
            if (request.getContentLengthLong() > MAX_BYTES + 8192L) throw invalid();
            body = request.getInputStream().readNBytes(MAX_BYTES + 8193);
            if (body.length > MAX_BYTES + 8192) throw invalid();
            byte[] start = ("--" + boundary + "\r\n").getBytes(StandardCharsets.US_ASCII);
            byte[] separator = "\r\n\r\n".getBytes(StandardCharsets.US_ASCII);
            byte[] end = ("\r\n--" + boundary + "--").getBytes(StandardCharsets.US_ASCII);
            if (!at(body, 0, start)) throw invalid();
            int headerEnd = indexOf(body, separator, start.length, Math.min(body.length, 4096));
            if (headerEnd < 0) throw invalid();
            int contentEnd = indexOf(body, end, headerEnd + 4, body.length);
            if (contentEnd < 0 || body.length != contentEnd + end.length && !(body.length == contentEnd + end.length + 2 && at(body, body.length - 2, new byte[]{13, 10}))) throw invalid();
            String headers = new String(body, start.length, headerEnd - start.length, StandardCharsets.US_ASCII);
            Map<String, String> values = new java.util.HashMap<>();
            for (String line : headers.split("\r\n")) {
                int colon = line.indexOf(':');
                if (colon < 1 || values.put(line.substring(0, colon).toLowerCase(Locale.ROOT), line.substring(colon + 1).strip()) != null) throw invalid();
            }
            var disposition = org.springframework.http.ContentDisposition.parse(values.getOrDefault("content-disposition", ""));
            if (!"form-data".equals(disposition.getType()) || !"capture".equals(disposition.getName())) throw invalid();
            String declared = MediaType.parseMediaType(values.getOrDefault("content-type", "")).toString();
            capture = Arrays.copyOfRange(body, headerEnd + 4, contentEnd);
            if (capture.length == 0 || capture.length > MAX_BYTES || !declared.equals(detect(capture))) throw invalid();
            // 첫 part 뒤에 몰래 끼워 넣은 다른 part를 이미지의 일부로 받지 않는다.
            if (indexOf(capture, ("\r\n--" + boundary + "\r\n").getBytes(StandardCharsets.US_ASCII), 0, capture.length) >= 0) throw invalid();
            var result = new MemoryCapture(capture, declared);
            capture = null;
            return result;
        } catch (IOException | IllegalArgumentException exception) { throw invalid(); }
        finally { if (body != null) Arrays.fill(body, (byte) 0); if (capture != null) Arrays.fill(capture, (byte) 0); }
    }

    public static String detect(byte[] value) {
        if (at(value, 0, new byte[]{(byte) 137, 80, 78, 71, 13, 10, 26, 10})) return "image/png";
        if (at(value, 0, new byte[]{(byte) 255, (byte) 216, (byte) 255})) return "image/jpeg";
        if (at(value, 0, "RIFF".getBytes(StandardCharsets.US_ASCII)) && at(value, 8, "WEBP".getBytes(StandardCharsets.US_ASCII))) return "image/webp";
        if (at(value, 4, "ftyp".getBytes(StandardCharsets.US_ASCII)) && value.length >= 16) {
            long size = Integer.toUnsignedLong(java.nio.ByteBuffer.wrap(value, 0, 4).getInt());
            if (size < 16 || size > value.length || size > 256) throw invalid();
            for (int i = 8; i + 4 <= size; i += 4) {
                if (i == 12) continue;
                String brand = new String(value, i, 4, StandardCharsets.US_ASCII);
                if (java.util.Set.of("heic", "heix", "hevc", "hevx").contains(brand)) return "image/heic";
            }
        }
        throw invalid();
    }
    public byte[] bytes() { return bytes; }
    public String mediaType() { return mediaType; }
    @Override public void close() { Arrays.fill(bytes, (byte) 0); }
    private static boolean at(byte[] input, int offset, byte[] needle) {
        if (offset < 0 || input.length - offset < needle.length) return false;
        for (int i = 0; i < needle.length; i++) if (input[offset + i] != needle[i]) return false;
        return true;
    }
    private static int indexOf(byte[] input, byte[] needle, int offset, int end) {
        for (int i = offset; i <= end - needle.length; i++) if (at(input, i, needle)) return i;
        return -1;
    }
    private static BusinessException invalid() { return new BusinessException(Be1AuthAiErrorCode.WRITE_REJECTED_VALIDATION); }
}
