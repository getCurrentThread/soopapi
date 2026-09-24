package com.github.getcurrentthread.soopapi.config;

import java.time.Duration;

/**
 * {@link com.github.getcurrentthread.soopapi.SOOPClient} 설정을 담는 불변 설정 클래스입니다.
 *
 * <p>개별 채팅 연결의 세부 설정은 {@link SOOPChatConfig}로 지정합니다.
 */
public class SOOPClientConfig {
    private final Duration connectionTimeout;
    private final int maxRetryAttempts;

    private static final Duration DEFAULT_CONNECTION_TIMEOUT = Duration.ofSeconds(15);
    private static final int DEFAULT_MAX_RETRY_ATTEMPTS = 5;

    private SOOPClientConfig(Builder builder) {
        this.connectionTimeout = builder.connectionTimeout;
        this.maxRetryAttempts = builder.maxRetryAttempts;
    }

    /**
     * REST API 클라이언트({@code SOOPHttpClient})의 연결 타임아웃을 반환합니다.
     *
     * <p>{@code SOOPClient}의 {@code auth()}, {@code live()}, {@code channel()} API 호출에 적용됩니다. 채팅
     * WebSocket 연결의 타임아웃은 {@link SOOPChatConfig#getConnectionTimeout()}을 따릅니다.
     *
     * @return 연결 타임아웃 (항상 0보다 큼)
     */
    public Duration getConnectionTimeout() {
        return connectionTimeout;
    }

    /**
     * WebSocket 재연결 최대 재시도 횟수의 기본값을 반환합니다.
     *
     * <p>{@code SOOPClient.add(String)} / {@code SOOPClient.chat(String)}처럼 스트리머 ID만으로 등록한 채팅
     * 클라이언트에 적용됩니다. {@link SOOPChatConfig}를 직접 전달해 등록한 경우에는 {@link
     * SOOPChatConfig#getMaxRetryAttempts()}가 사용됩니다.
     *
     * @return 최대 재시도 횟수
     */
    public int getMaxRetryAttempts() {
        return maxRetryAttempts;
    }

    /** SOOPClientConfig 빌더 클래스 */
    public static class Builder {
        private Duration connectionTimeout = DEFAULT_CONNECTION_TIMEOUT;
        private int maxRetryAttempts = DEFAULT_MAX_RETRY_ATTEMPTS;

        /**
         * REST API 클라이언트({@code SOOPHttpClient})의 연결 타임아웃을 설정합니다.
         *
         * @param connectionTimeout 연결 타임아웃 (0보다 커야 함), 기본값 15초
         * @return 빌더 인스턴스
         * @throws IllegalArgumentException {@code connectionTimeout}이 null이거나 0 이하인 경우
         */
        public Builder connectionTimeout(Duration connectionTimeout) {
            if (connectionTimeout == null) {
                throw new IllegalArgumentException("connectionTimeout must not be null");
            }
            if (connectionTimeout.isNegative() || connectionTimeout.isZero()) {
                throw new IllegalArgumentException("connectionTimeout must be positive");
            }
            this.connectionTimeout = connectionTimeout;
            return this;
        }

        /**
         * WebSocket 재연결 최대 재시도 횟수의 기본값을 설정합니다.
         *
         * <p>{@code SOOPClient.add(String)} / {@code SOOPClient.chat(String)}로 등록한 채팅 클라이언트에 적용됩니다.
         *
         * @param maxRetryAttempts 최대 재시도 횟수 (0 이상), 기본값 5
         * @return 빌더 인스턴스
         * @throws IllegalArgumentException {@code maxRetryAttempts}가 음수인 경우
         */
        public Builder maxRetryAttempts(int maxRetryAttempts) {
            if (maxRetryAttempts < 0) {
                throw new IllegalArgumentException("maxRetryAttempts must be >= 0");
            }
            this.maxRetryAttempts = maxRetryAttempts;
            return this;
        }

        /**
         * SOOPClientConfig 인스턴스를 생성합니다.
         *
         * @return 구성된 SOOPClientConfig 인스턴스
         */
        public SOOPClientConfig build() {
            if (connectionTimeout.isNegative() || connectionTimeout.isZero()) {
                throw new IllegalArgumentException("connectionTimeout must be positive");
            }
            if (maxRetryAttempts < 0) {
                throw new IllegalArgumentException("maxRetryAttempts must be >= 0");
            }
            return new SOOPClientConfig(this);
        }
    }
}
