package com.github.getcurrentthread.soopapi.api;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import javax.net.ssl.SSLSession;

/** 네트워크 없이 API 클래스를 테스트하기 위한 고정 응답. 헤더를 주지 않으면 비어 있다. */
record StubHttpResponse(int statusCode, String body, Map<String, List<String>> headerValues)
        implements HttpResponse<String> {

    StubHttpResponse(int statusCode, String body) {
        this(statusCode, body, Map.of());
    }

    @Override
    public HttpRequest request() {
        return null;
    }

    @Override
    public Optional<HttpResponse<String>> previousResponse() {
        return Optional.empty();
    }

    @Override
    public HttpHeaders headers() {
        return HttpHeaders.of(headerValues, (name, value) -> true);
    }

    @Override
    public Optional<SSLSession> sslSession() {
        return Optional.empty();
    }

    @Override
    public URI uri() {
        return URI.create("https://example.invalid/");
    }

    @Override
    public HttpClient.Version version() {
        return HttpClient.Version.HTTP_1_1;
    }
}
