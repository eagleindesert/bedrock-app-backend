package com.bedrock.app.ocr.config;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.multipart.MultipartHttpServletRequest;
import org.springframework.web.multipart.MultipartResolver;
import org.springframework.web.multipart.support.StandardServletMultipartResolver;

@Configuration(proxyBeanMethods = false)
public class OcrMultipartConfiguration {
    @Bean(name = "multipartResolver")
    public MultipartResolver multipartResolver() {
        var standard = new StandardServletMultipartResolver();
        return new MultipartResolver() {
            @Override public boolean isMultipart(HttpServletRequest request) {
                return !request.getRequestURI().equals("/api/v1/ocr/timetables") && standard.isMultipart(request);
            }
            @Override public MultipartHttpServletRequest resolveMultipart(HttpServletRequest request) { return standard.resolveMultipart(request); }
            @Override public void cleanupMultipart(MultipartHttpServletRequest request) { standard.cleanupMultipart(request); }
        };
    }
}
