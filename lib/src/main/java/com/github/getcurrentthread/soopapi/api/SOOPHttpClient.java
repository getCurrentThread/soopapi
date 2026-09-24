package com.github.getcurrentthread.soopapi.api;

import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;

public class SOOPHttpClient implements AutoCloseable {
    private static final String USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36";
    private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(15);

    private final HttpClient httpClient;
    private final CookieManager cookieManager;
    private final Duration connectionTimeout;

    public SOOPHttpClient() {
        this(DEFAULT_TIMEOUT);
    }

    public SOOPHttpClient(Duration connectionTimeout) {
        this.connectionTimeout = connectionTimeout != null ? connectionTimeout : DEFAULT_TIMEOUT;
        this.cookieManager = new CookieManager(null, CookiePolicy.ACCEPT_ORIGINAL_SERVER);
        this.httpClient =
                HttpClient.newBuilder()
                        .connectTimeout(this.connectionTimeout)
                        .cookieHandler(cookieManager)
                        .followRedirects(HttpClient.Redirect.NORMAL)
                        .build();
    }

    public CompletableFuture<HttpResponse<String>> get(String url) {
        HttpRequest request =
                HttpRequest.newBuilder()
                        .uri(URI.create(url))
                        .GET()
                        .header("User-Agent", USER_AGENT)
                        .timeout(connectionTimeout)
                        .build();
        return httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString());
    }

    public CompletableFuture<HttpResponse<String>> post(
            String url, String body, String contentType) {
        HttpRequest request =
                HttpRequest.newBuilder()
                        .uri(URI.create(url))
                        .header("User-Agent", USER_AGENT)
                        .header("Content-Type", contentType)
                        .POST(HttpRequest.BodyPublishers.ofString(body))
                        .timeout(connectionTimeout)
                        .build();
        return httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString());
    }

    public CompletableFuture<HttpResponse<String>> postForm(String url, String formData) {
        return post(url, formData, "application/x-www-form-urlencoded");
    }

    public CompletableFuture<HttpResponse<String>> postForm(
            String url, String formData, String cookieHeader) {
        if (cookieHeader == null || cookieHeader.isEmpty()) {
            return postForm(url, formData);
        }
        HttpRequest request =
                HttpRequest.newBuilder()
                        .uri(URI.create(url))
                        .header("User-Agent", USER_AGENT)
                        .header("Content-Type", "application/x-www-form-urlencoded")
                        .header("Cookie", cookieHeader)
                        .POST(HttpRequest.BodyPublishers.ofString(formData))
                        .timeout(connectionTimeout)
                        .build();
        return httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString());
    }

    public CookieManager getCookieManager() {
        return cookieManager;
    }

    public HttpClient getHttpClient() {
        return httpClient;
    }

    /** 새 요청을 받지 않도록 하고 즉시 반환합니다. 진행 중인 요청은 끝까지 처리됩니다. */
    public void shutdown() {
        httpClient.shutdown();
    }

    @Override
    public void close() {
        httpClient.close();
    }
}
