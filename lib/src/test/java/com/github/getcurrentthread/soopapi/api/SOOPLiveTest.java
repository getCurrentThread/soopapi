package com.github.getcurrentthread.soopapi.api;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.net.URI;
import java.net.http.HttpResponse;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;

import org.junit.jupiter.api.Test;

class SOOPLiveTest {

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
