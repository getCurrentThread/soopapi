package com.github.getcurrentthread.soopapi.api.model;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class AuthCookieTest {

    @Test
    void toStringRedactsSecretsAndOmitsRawResponse() {
        AuthCookie cookie =
                new AuthCookie(
                        "user1",
                        true,
                        "{\"RESULT\":1,\"x\":\"RAWSECRET\"}",
                        "AUTHSECRET",
                        "ABROADCHKSECRET",
                        "ABROADVODSECRET",
                        "BBSSECRET",
                        "RDBSECRET",
                        "USERSECRET",
                        "AUSECRET",
                        "AU3RDSECRET",
                        "AUSASECRET",
                        "");

        String s = cookie.toString();

        assertFalse(s.contains("SECRET"), s);
        assertFalse(s.contains("rawResponse"), s);
        assertTrue(s.contains("userId=user1"), s);
        assertTrue(s.contains("success=true"), s);
        assertTrue(s.contains("authTicket=<redacted>"), s);
        assertTrue(s.contains("ausb=<empty>"), s);
    }

    @Test
    void toStringHandlesNullFields() {
        AuthCookie cookie =
                new AuthCookie(
                        "user1", false, null, null, null, null, null, null, null, null, null, null,
                        null);

        assertTrue(cookie.toString().contains("authTicket=<empty>"));
    }
}
