package com.github.getcurrentthread.soopapi.api;

import java.net.HttpCookie;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Level;
import java.util.logging.Logger;

import com.github.getcurrentthread.soopapi.api.model.AuthCookie;
import com.github.getcurrentthread.soopapi.exception.AuthenticationException;
import com.github.getcurrentthread.soopapi.exception.SOOPChatException;
import com.google.gson.JsonObject;

public class SOOPAuth {
    private static final Logger LOGGER = Logger.getLogger(SOOPAuth.class.getName());
    private static final String LOGIN_URL = "https://login.sooplive.co.kr/app/LoginAction.php";

    private final SOOPHttpClient httpClient;

    public SOOPAuth(SOOPHttpClient httpClient) {
        this.httpClient = httpClient;
    }

    public CompletableFuture<AuthCookie> signIn(String userId, String password) {
        String formData =
                String.format(
                        "szWork=login&szType=json&szUid=%s&szPassword=%s",
                        URLEncoder.encode(userId, StandardCharsets.UTF_8),
                        URLEncoder.encode(password, StandardCharsets.UTF_8));

        return httpClient
                .postForm(LOGIN_URL, formData)
                .thenApply(
                        response -> {
                            if (response.statusCode() != 200) {
                                throw new SOOPChatException(
                                        "Login request failed. Status code: "
                                                + response.statusCode());
                            }

                            try {
                                JsonObject json = JsonFields.parseObject(response.body());
                                int result = JsonFields.getInt(json, "RESULT", 0);

                                Map<String, String> cookies =
                                        parseCookies(response.headers().allValues("set-cookie"));

                                if (result == 1) {
                                    return new AuthCookie(
                                            userId,
                                            true,
                                            response.body(),
                                            cookies.getOrDefault("AuthTicket", ""),
                                            cookies.getOrDefault("AbroadChk", ""),
                                            cookies.getOrDefault("AbroadVod", ""),
                                            cookies.getOrDefault("BbsTicket", ""),
                                            cookies.getOrDefault("RDB", ""),
                                            cookies.getOrDefault("UserTicket", ""),
                                            cookies.getOrDefault("_au", ""),
                                            cookies.getOrDefault("_au3rd", ""),
                                            cookies.getOrDefault("_ausa", ""),
                                            cookies.getOrDefault("_ausb", ""));
                                } else {
                                    String reason =
                                            JsonFields.getString(json, "REASON", "unknown error");
                                    throw new AuthenticationException("Login failed: " + reason);
                                }
                            } catch (AuthenticationException e) {
                                throw e;
                            } catch (Exception e) {
                                LOGGER.log(Level.WARNING, "Error parsing login response", e);
                                throw new SOOPChatException("Failed to parse login response", e);
                            }
                        });
    }

    private Map<String, String> parseCookies(List<String> setCookieHeaders) {
        Map<String, String> cookies = new HashMap<>();
        for (String header : setCookieHeaders) {
            try {
                List<HttpCookie> parsed = HttpCookie.parse(header);
                for (HttpCookie cookie : parsed) {
                    cookies.put(cookie.getName(), cookie.getValue());
                }
            } catch (Exception e) {
                // 헤더에는 AuthTicket 같은 자격 증명 값이 들어 있으므로 헤더도, 그 내용이 실릴 수 있는 예외도 로그에 남기지 않는다.
                LOGGER.fine(
                        () ->
                                "Skipping a Set-Cookie header that cannot be parsed: "
                                        + e.getClass().getName());
            }
        }
        return cookies;
    }
}
