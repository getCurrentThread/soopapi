package com.github.getcurrentthread.soopapi.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.net.http.HttpResponse;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

import org.junit.jupiter.api.Test;

import com.github.getcurrentthread.soopapi.exception.AuthenticationException;

class SOOPAuthTest {

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

    private static AuthenticationException signInFailure(String body) {
        try (SOOPHttpClient http = stubPostForm(body)) {
            CompletionException ex =
                    assertThrows(
                            CompletionException.class,
                            () -> new SOOPAuth(http).signIn("user1", "pw1").join());
            return assertInstanceOf(AuthenticationException.class, ex.getCause());
        }
    }

    private static SOOPHttpClient stubPostForm(String body) {
        return new SOOPHttpClient() {
            @Override
            public CompletableFuture<HttpResponse<String>> postForm(String url, String formData) {
                return CompletableFuture.completedFuture(new StubHttpResponse(200, body));
            }
        };
    }
}
