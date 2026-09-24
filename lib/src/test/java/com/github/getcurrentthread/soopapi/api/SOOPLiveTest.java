package com.github.getcurrentthread.soopapi.api;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.net.http.HttpResponse;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.jupiter.api.Test;

import com.github.getcurrentthread.soopapi.api.model.AuthCookie;
import com.github.getcurrentthread.soopapi.exception.AdultBroadcastException;
import com.github.getcurrentthread.soopapi.exception.AuthenticationException;
import com.github.getcurrentthread.soopapi.exception.SOOPChatException;

class SOOPLiveTest {

    // 19금 방송에 볼 수 없는 요청으로 물었을 때와 같은 구조의 합성 본문: RESULT=-6, REASON과 채팅 접속 정보가 없다
    private static final String ADULT_ONLY =
            "{\"CHANNEL\":{\"RESULT\":-6,\"TITLE\":\"title1\",\"BPS\":\"1000\",\"BTIME\":1000,"
                    + "\"CATE\":\"00000000\",\"RESOLUTION\":\"1280x720\",\"geo_cc\":\"XX\","
                    + "\"geo_rc\":\"XX\",\"svc_lang\":\"xx_XX\"}}";

    @Test
    void chatDomainIsLowercasedIndependentOfDefaultLocale() {
        String body =
                "{\"CHANNEL\":{\"RESULT\":1,\"BJID\":\"bj1\",\"TITLE\":\"title1\","
                        + "\"CHDOMAIN\":\"CHAT-0A1B2C3D.EXAMPLE.INVALID\",\"CHATNO\":\"1\","
                        + "\"FTK\":\"ftk1\",\"CHPT\":\"8000\"}}";

        Locale original = Locale.getDefault();
        // 터키어 로케일에서는 기본 toLowerCase()가 'I'를 점 없는 'ı'로 바꾼다
        Locale.setDefault(Locale.forLanguageTag("tr-TR"));
        try (SOOPHttpClient http = stubPostForm(body)) {
            String chatDomain = new SOOPLive(http).detail("bj1", "1").join().chatDomain();

            assertEquals("chat-0a1b2c3d.example.invalid", chatDomain);
            assertDoesNotThrow(() -> new URI("wss", null, chatDomain, 8001, "/", null, null));
        } finally {
            Locale.setDefault(original);
        }
    }

    @Test
    void adultOnly_anonymousRequestAsksForAnAgeVerifiedLogin() {
        AdultBroadcastException e = assertInstanceOf(AdultBroadcastException.class, failure(null));

        assertInstanceOf(AuthenticationException.class, e);
        String message = e.getMessage();
        assertTrue(message.contains("bj1"), message);
        assertTrue(message.contains("19+"), message);
        assertTrue(message.contains("anonymously"), message);
        assertTrue(message.contains("age-verified"), message);
        assertTrue(message.contains("SOOPChatConfig.Builder.authCookie()"), message);
        assertTrue(message.contains("detail(bid, bno, authCookie)"), message);
    }

    @Test
    void adultOnly_authenticatedRequestSaysTheAccountIsNotAllowed() {
        AdultBroadcastException e =
                assertInstanceOf(AdultBroadcastException.class, failure(authCookie(true)));

        String message = e.getMessage();
        assertTrue(message.contains("bj1"), message);
        assertTrue(message.contains("19+"), message);
        assertTrue(message.contains("signed-in account is not allowed"), message);
        assertFalse(message.contains("anonymously"), message);
    }

    @Test
    void adultOnly_unauthenticatedCookieCountsAsAnonymous() {
        Throwable e = failure(authCookie(false));

        assertInstanceOf(AdultBroadcastException.class, e);
        assertTrue(e.getMessage().contains("anonymously"), e.getMessage());
    }

    @Test
    void errorWithoutReason_reportsTheResultCode() {
        Throwable e = failure("{\"CHANNEL\":{\"RESULT\":-3}}", null);

        assertEquals(SOOPChatException.class, e.getClass());
        assertEquals("API error: RESULT=-3", e.getMessage());
    }

    @Test
    void errorWithoutChannelOrReason_reportsTheResultCode() {
        Throwable e = failure("{\"RESULT\":-2}", null);

        assertEquals("API error: RESULT=-2", e.getMessage());
    }

    @Test
    void errorWithReason_keepsTheReason() {
        Throwable e = failure("{\"CHANNEL\":{\"RESULT\":-1,\"REASON\":\"reason1\"}}", null);

        assertEquals("API error: reason1", e.getMessage());
    }

    @Test
    void confirmAdult_isAlwaysFalse() {
        // 19금 확인을 사용자 대신 하지 않는다. 연령 인증된 로그인은 쿠키만으로 채팅 접속 정보를 받는다.
        for (AuthCookie authCookie : Arrays.asList(null, authCookie(false), authCookie(true))) {
            String formData = request(authCookie).formData();

            assertTrue(formData.contains("&confirm_adult=false&"), formData);
            assertFalse(formData.contains("confirm_adult=true"), formData);
        }
    }

    @Test
    void loginCookie_isSentOnlyForAnAuthenticatedCookie() {
        // 19금 방송의 채팅 접속 정보를 받게 하는 것은 이 로그인 쿠키다
        assertNull(request(null).cookieHeader());
        assertNull(request(authCookie(false)).cookieHeader());
        assertEquals(
                "AuthTicket=ticket1; _au=au1; UserTicket=ticket2; RDB=rdb1",
                request(authCookie(true)).cookieHeader());
    }

    @Test
    void unparsableResponse_isNotLoggedOrThrown() {
        // 객체가 아닌 JSON. Gson의 getAsJsonObject()는 이런 본문을 예외 메시지에 그대로 싣는다
        String body = "[{\"CHANNEL\":{\"RESULT\":1,\"FTK\":\"ftk-secret-1\"}}]";

        try (LogCapture logs = new LogCapture(SOOPLive.class)) {
            Throwable e = failure(body, authCookie(true));

            assertEquals("Failed to parse live stream info", e.getMessage());
            assertEquals(1, logs.size(), "The parse failure is still reported");
            for (String text : List.of(LogCapture.stackTrace(e), logs.text())) {
                for (String secret : List.of("ftk-secret-1", "ticket1", "ticket2", "au1", "rdb1")) {
                    assertFalse(text.contains(secret), text);
                }
            }
        }
    }

    /** RESULT=-6 응답을 받은 detail()의 실패 원인. */
    private static Throwable failure(AuthCookie authCookie) {
        return failure(ADULT_ONLY, authCookie);
    }

    private static Throwable failure(String body, AuthCookie authCookie) {
        try (SOOPHttpClient http = stubPostForm(body)) {
            CompletionException ex =
                    assertThrows(
                            CompletionException.class,
                            () -> new SOOPLive(http).detail("bj1", "1000", authCookie).join());
            return ex.getCause();
        }
    }

    /** detail()이 보낸 요청의 폼 본문과 Cookie 헤더(없으면 null). */
    private record SentRequest(String formData, String cookieHeader) {}

    /** detail()이 보낸 요청. 응답은 RESULT=-6이라 요청 뒤 실패한다. */
    private static SentRequest request(AuthCookie authCookie) {
        List<SentRequest> requests = new CopyOnWriteArrayList<>();
        try (SOOPHttpClient http =
                new SOOPHttpClient() {
                    @Override
                    public CompletableFuture<HttpResponse<String>> postForm(
                            String url, String formData, String cookieHeader) {
                        requests.add(new SentRequest(formData, cookieHeader));
                        return CompletableFuture.completedFuture(
                                new StubHttpResponse(200, ADULT_ONLY));
                    }
                }) {
            assertThrows(
                    CompletionException.class,
                    () -> new SOOPLive(http).detail("bj1", "1000", authCookie).join());
        }
        assertEquals(1, requests.size());
        return requests.getFirst();
    }

    private static AuthCookie authCookie(boolean success) {
        return new AuthCookie(
                "user1", success, null, "ticket1", null, null, null, "rdb1", "ticket2", "au1", null,
                null, null);
    }

    private static SOOPHttpClient stubPostForm(String body) {
        return new SOOPHttpClient() {
            @Override
            public CompletableFuture<HttpResponse<String>> postForm(
                    String url, String formData, String cookieHeader) {
                return CompletableFuture.completedFuture(new StubHttpResponse(200, body));
            }
        };
    }
}
