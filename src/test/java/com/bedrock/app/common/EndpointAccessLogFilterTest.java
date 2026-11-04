package com.bedrock.app.common;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.bedrock.app.common.auth.SessionPrincipal;
import com.bedrock.app.common.filter.EndpointAccessLogFilter;
import jakarta.servlet.ServletException;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.servlet.HandlerMapping;

import java.util.UUID;

import static org.assertj.core.api.Assertions.*;

class EndpointAccessLogFilterTest {
    @Test
    void logsRouteAndStatusWithoutBodyQueryCookieOrPathTokens() throws Exception {
        var logger = (Logger) LoggerFactory.getLogger("ACCESS_LOGGER");
        var appender = new ListAppender<ILoggingEvent>();
        appender.start();
        logger.addAppender(appender);
        try {
            var request = new MockHttpServletRequest("POST", "/api/tokens/secret-path;jsessionid=secret-session");
            request.setQueryString("token=secret-query");
            request.setContent("secret-body".getBytes());
            request.addHeader("Cookie", "BEDROCK_SESSION=secret-cookie");
            request.addHeader("X-Forwarded-For", "203.0.113.7, 127.0.0.1");
            request.setRemoteAddr("127.0.0.1");
            var principal = new SessionPrincipal(UUID.randomUUID(), UUID.randomUUID(), "WEB", false);
            request.getSession().setAttribute(SessionPrincipal.SESSION_ATTRIBUTE, principal);
            var response = new MockHttpServletResponse();

            new EndpointAccessLogFilter().doFilter(request, response, (req, res) -> {
                req.setAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE, "/api/tokens/{token}");
                response.setStatus(201);
            });

            assertThat(response.getHeader("Cache-Control")).isEqualTo("no-store");
            assertThat(appender.list).hasSize(1);
            assertThat(appender.list.getFirst().getFormattedMessage())
                    .contains("POST", "/api/tokens/{token}", "status=201", "ip=203.0.113.7", principal.userId().toString())
                    .doesNotContain("secret-");
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }
    }

    @Test
    void unexpectedFailureIsLoggedAs500AndUntrustedHeadersAreIgnored() {
        var logger = (Logger) LoggerFactory.getLogger("ACCESS_LOGGER");
        var appender = new ListAppender<ILoggingEvent>();
        appender.start();
        logger.addAppender(appender);
        try {
            var request = new MockHttpServletRequest("GET", "/secret-unmatched-path");
            request.setRemoteAddr("198.51.100.2");
            request.addHeader("X-Forwarded-For", "secret-forged-ip\nforged-log");
            assertThatThrownBy(() -> new EndpointAccessLogFilter().doFilter(request,
                    new MockHttpServletResponse(), (req, res) -> { throw new ServletException("secret-body"); }))
                    .isInstanceOf(ServletException.class);
            assertThat(appender.list.getFirst().getFormattedMessage())
                    .contains("status=500", "ip=198.51.100.2", "uri=[unmapped]")
                    .doesNotContain("secret-", "forged-log");
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }
    }
}
