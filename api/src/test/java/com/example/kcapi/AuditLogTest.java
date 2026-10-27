package com.example.kcapi;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.mock.web.MockHttpServletRequest;

class AuditLogTest {

    private ListAppender<ILoggingEvent> appender;
    private Logger audit;

    @BeforeEach
    void attach() {
        audit = (Logger) LoggerFactory.getLogger("audit");
        appender = new ListAppender<>();
        appender.start();
        audit.addAppender(appender);
    }

    @AfterEach
    void detach() {
        audit.detachAppender(appender);
    }

    private String lastMessage() {
        return appender.list.get(appender.list.size() - 1).getFormattedMessage();
    }

    @Test
    void loginSuccessIsInfoAndHasUsername() {
        AuditLog.loginSuccess("a@example.com", "kc-1", "203.0.113.5");
        var event = appender.list.get(0);
        assertEquals(Level.INFO, event.getLevel());
        assertEquals("event=login result=success username=a@example.com user_id=kc-1 client_ip=203.0.113.5",
                event.getFormattedMessage());
    }

    @Test
    void loginFailureIsWarnAndHasReasonAndUsername() {
        AuditLog.loginFailure("a@example.com", AuditLog.Reason.PASSWORD_MISMATCH, "203.0.113.5");
        var event = appender.list.get(0);
        assertEquals(Level.WARN, event.getLevel());
        assertEquals("event=login result=failure reason=PASSWORD_MISMATCH username=a@example.com client_ip=203.0.113.5",
                event.getFormattedMessage());
    }

    @Test
    void apiKeyRejectionNeverContainsTheReceivedKey() {
        var request = new MockHttpServletRequest("POST", "/api/login");
        request.addHeader("X-API-Key", "super-secret-wrong-key");
        request.addHeader("X-Forwarded-For", "198.51.100.7, 10.0.0.1");

        AuditLog.apiKeyRejected(request, AuditLog.Reason.API_KEY_MISMATCH);

        var event = appender.list.get(0);
        assertEquals(Level.WARN, event.getLevel());
        assertEquals("event=api_key result=rejected reason=API_KEY_MISMATCH method=POST path=/api/login client_ip=198.51.100.7",
                event.getFormattedMessage());
        assertFalse(event.getFormattedMessage().contains("super-secret-wrong-key"));
    }

    @Test
    void clientIpFallsBackToRemoteAddrWithoutForwardedHeader() {
        var request = new MockHttpServletRequest("GET", "/api/users");
        request.setRemoteAddr("10.1.2.3");
        assertEquals("10.1.2.3", AuditLog.clientIp(request));
    }

    @Test
    void controlCharactersInUsernameCannotForgeLogLines() {
        // 改行を含むユーザー名で、偽の成功ログ行を作れないこと
        AuditLog.loginFailure("evil@example.com\nevent=login result=success username=admin",
                AuditLog.Reason.USER_NOT_FOUND, "203.0.113.5");
        String message = lastMessage();
        assertFalse(message.contains("\n"));
        assertFalse(message.contains("\r"));
        assertEquals(1, appender.list.size());
        assertTrue(message.contains("username=evil@example.com_event=login_result=success_username=admin"));
    }

    @Test
    void veryLongUsernameIsTruncated() {
        AuditLog.loginFailure("x".repeat(5000), AuditLog.Reason.USER_NOT_FOUND, "203.0.113.5");
        assertTrue(lastMessage().length() < 400);
    }
}
