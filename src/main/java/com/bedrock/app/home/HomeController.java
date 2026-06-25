package com.bedrock.app.home;

import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class HomeController {

    @GetMapping(value = "/", produces = MediaType.TEXT_HTML_VALUE)
    public String home() {
        return """
            <!DOCTYPE html>
            <html lang="ko">
            <head>
              <meta charset="UTF-8">
              <title>Bedrock App</title>
            </head>
            <body>
              <h1>✅ Bedrock App Backend is running</h1>
              <p>이 서버는 REST API 백엔드입니다.</p>
              <ul>
                <li>POST /api/auth/signup</li>
                <li>POST /api/auth/login</li>
                <li>POST /api/auth/logout</li>
                <li>GET  /api/debug</li>
              </ul>
            </body>
            </html>
            """;
    }
}
