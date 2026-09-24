package com.github.getcurrentthread.soopapi.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

import org.junit.jupiter.api.Test;

import com.github.getcurrentthread.soopapi.api.model.AuthCookie;
import com.github.getcurrentthread.soopapi.exception.AuthenticationException;
import com.github.getcurrentthread.soopapi.exception.SOOPChatException;

class SOOPAuthTest {

    private static final String PASSWORD = "password-secret-1";

    @Test
    void nullReasonIsReportedAsAuthenticationFailure() {
        AuthenticationException e = signInFailure("{\"RESULT\":-1,\"REASON\":null}");
        assertEquals("Login failed: unknown error", e.getMessage());
    }

    @Test
    void nullResultIsReportedAsAuthenticationFailure() {
        AuthenticationException e = signInFailure("{\"RESULT\":null,\"REASON\":\"reason1\"}");
        assertEquals("Login failed: reason1", e.getMessage());
    }

    @Test
    void unparsableSetCookieHeader_isSkippedWithoutLoggingItsValue() {
        // 이름=값 쌍이 아니어서 HttpCookie.parse가 거부하는 헤더
        Map<String, List<String>> headers = Map.of("set-cookie", List.of("ticket-secret-1"));

        try (LogCapture logs = new LogCapture(SOOPAuth.class);
                SOOPHttpClient http = stubPostForm("{\"RESULT\":1}", headers)) {
            AuthCookie cookie = new SOOPAuth(http).signIn("user1", PASSWORD).join();

            assertFalse(cookie.isAuthenticated());
            assertEquals(1, logs.size(), "The skipped header is still reported");
            assertNotLeaked(logs.text(), "ticket-secret-1");
        }
    }

    @Test
    void unparsableLoginResponse_isNotLoggedOrThrown() {
        // 객체가 아닌 JSON. Gson의 getAsJsonObject()는 이런 본문을 예외 메시지에 그대로 싣는다
        String body = "[\"login-secret-1\"]";

        try (LogCapture logs = new LogCapture(SOOPAuth.class);
                SOOPHttpClient http = stubPostForm(body, Map.of())) {
            CompletionException ex =
                    assertThrows(
                            CompletionException.class,
                            () -> new SOOPAuth(http).signIn("user1", PASSWORD).join());

            SOOPChatException e = assertInstanceOf(SOOPChatException.class, ex.getCause());
            assertEquals("Failed to parse login response", e.getMessage());
            assertNotLeaked(LogCapture.stackTrace(ex), "login-secret-1");
            assertNotLeaked(logs.text(), "login-secret-1");
        }
    }

    /** 응답 본문·쿠키 값과 로그인 폼(비밀번호)이 {@code text}에 없는지 확인한다. */
    private static void assertNotLeaked(String text, String secret) {
        assertFalse(text.contains(secret), text);
        assertFalse(text.contains(PASSWORD), text);
    }

    private static AuthenticationException signInFailure(String body) {
        try (SOOPHttpClient http = stubPostForm(body, Map.of())) {
            CompletionException ex =
                    assertThrows(
                            CompletionException.class,
                            () -> new SOOPAuth(http).signIn("user1", PASSWORD).join());
            return assertInstanceOf(AuthenticationException.class, ex.getCause());
        }
    }

    private static SOOPHttpClient stubPostForm(String body, Map<String, List<String>> headers) {
        return new SOOPHttpClient() {
            @Override
            public CompletableFuture<HttpResponse<String>> postForm(String url, String formData) {
                return CompletableFuture.completedFuture(new StubHttpResponse(200, body, headers));
            }
        };
    }
}
