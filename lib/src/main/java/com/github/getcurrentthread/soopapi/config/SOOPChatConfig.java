package com.github.getcurrentthread.soopapi.config;

import java.time.Duration;

import javax.net.ssl.SSLContext;

import com.github.getcurrentthread.soopapi.api.model.AuthCookie;

/**
 * 채팅 클라이언트 설정을 담는 불변 설정 클래스입니다.
 *
 * <p>{@code authCookie}를 설정하지 않으면 익명(읽기 전용) 모드로 연결됩니다. 익명 모드에서는 채팅 메시지를 수신할 수 있지만, {@code
 * sendChat()}을 호출하면 {@link com.github.getcurrentthread.soopapi.exception.AuthenticationException}이
 * 발생합니다.
 */
public class SOOPChatConfig {
    private final String bid;
    private final String bno;
    private final SSLContext sslContext;
    private final Duration connectionTimeout;
    private final int maxRetryAttempts;
    private final AuthCookie authCookie;
    private final long pingIntervalSeconds;
    private final long initialPacketDelayMs;

    private static final Duration DEFAULT_CONNECTION_TIMEOUT = Duration.ofSeconds(30);
    private static final int DEFAULT_MAX_RETRY_ATTEMPTS = 5;
    private static final long DEFAULT_PING_INTERVAL_SECONDS = 60;
    private static final long DEFAULT_INITIAL_PACKET_DELAY_MS = 1000;

    private SOOPChatConfig(Builder builder) {
        this.bid = builder.bid;
        this.bno = builder.bno;
        this.sslContext = builder.sslContext;
        this.connectionTimeout = builder.connectionTimeout;
        this.maxRetryAttempts = builder.maxRetryAttempts;
        this.authCookie = builder.authCookie;
        this.pingIntervalSeconds = builder.pingIntervalSeconds;
        this.initialPacketDelayMs = builder.initialPacketDelayMs;
    }

    /**
     * 방송인 ID를 반환합니다.
     *
     * @return 방송인 ID
     */
    public String getBid() {
        return bid;
    }

    /**
     * 방송 번호를 반환합니다.
     *
     * @return 방송 번호 (null일 수 있음)
     */
    public String getBno() {
        return bno;
    }

    /**
     * SSL Context를 반환합니다.
     *
     * @return SSL Context (null일 수 있음)
     */
    public SSLContext getSSLContext() {
        return sslContext;
    }

    /**
     * 연결 타임아웃을 반환합니다.
     *
     * @return 연결 타임아웃
     */
    public Duration getConnectionTimeout() {
        return connectionTimeout;
    }

    /**
     * 최대 재시도 횟수를 반환합니다.
     *
     * @return 최대 재시도 횟수
     */
    public int getMaxRetryAttempts() {
        return maxRetryAttempts;
    }

    /**
     * 인증 쿠키를 반환합니다.
     *
     * @return 인증 쿠키 (null일 수 있음)
     */
    public AuthCookie getAuthCookie() {
        return authCookie;
    }

    /**
     * 인증된 연결인지 확인합니다.
     *
     * @return 인증 여부
     */
    public boolean isAuthenticated() {
        return authCookie != null && authCookie.isAuthenticated();
    }

    /**
     * 핑 전송 간격(초)을 반환합니다.
     *
     * @return 핑 전송 간격(초)
     */
    public long getPingIntervalSeconds() {
        return pingIntervalSeconds;
    }

    /**
     * 초기 패킷 전송 후 대기 시간(밀리초)을 반환합니다.
     *
     * <p>이 값은 연결 동작에 아무런 영향을 주지 않습니다. CONNECT 패킷과 JOIN 패킷은 대기 없이 연달아 전송됩니다.
     *
     * @return 초기 패킷 대기 시간(밀리초)
     * @deprecated 효과가 없는 설정이며 향후 버전에서 제거될 예정입니다. CONNECT와 JOIN은 대기 없이 연달아 전송됩니다.
     */
    @Deprecated(forRemoval = true)
    public long getInitialPacketDelayMs() {
        return initialPacketDelayMs;
    }

    /** SOOPChatConfig 빌더 클래스 */
    public static class Builder {
        private String bid;
        private String bno;
        private SSLContext sslContext;
        private Duration connectionTimeout = DEFAULT_CONNECTION_TIMEOUT;
        private int maxRetryAttempts = DEFAULT_MAX_RETRY_ATTEMPTS;
        private AuthCookie authCookie;
        private long pingIntervalSeconds = DEFAULT_PING_INTERVAL_SECONDS;
        private long initialPacketDelayMs = DEFAULT_INITIAL_PACKET_DELAY_MS;

        /**
         * 방송인 ID를 설정합니다.
         *
         * @param bid 방송인 ID
         * @return 빌더 인스턴스
         */
        public Builder bid(String bid) {
            this.bid = bid;
            return this;
        }

        /**
         * 방송 번호를 설정합니다.
         *
         * @param bno 방송 번호
         * @return 빌더 인스턴스
         */
        public Builder bno(String bno) {
            this.bno = bno;
            return this;
        }

        /**
         * SSL Context를 설정합니다.
         *
         * @param sslContext SSL Context
         * @return 빌더 인스턴스
         */
        public Builder sslContext(SSLContext sslContext) {
            this.sslContext = sslContext;
            return this;
        }

        /**
         * 연결 타임아웃을 설정합니다.
         *
         * @param connectionTimeout 연결 타임아웃 (0보다 커야 함)
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
         * 최대 재시도 횟수를 설정합니다.
         *
         * @param maxRetryAttempts 최대 재시도 횟수
         * @return 빌더 인스턴스
         */
        public Builder maxRetryAttempts(int maxRetryAttempts) {
            this.maxRetryAttempts = maxRetryAttempts;
            return this;
        }

        /**
         * 인증 쿠키를 설정합니다.
         *
         * @param authCookie 인증 쿠키
         * @return 빌더 인스턴스
         */
        public Builder authCookie(AuthCookie authCookie) {
            this.authCookie = authCookie;
            return this;
        }

        /**
         * 핑 전송 간격(초)을 설정합니다.
         *
         * @param pingIntervalSeconds 핑 전송 간격(초), 기본값 60
         * @return 빌더 인스턴스
         */
        public Builder pingIntervalSeconds(long pingIntervalSeconds) {
            this.pingIntervalSeconds = pingIntervalSeconds;
            return this;
        }

        /**
         * 초기 패킷 전송 후 대기 시간(밀리초)을 설정합니다.
         *
         * <p>이 값은 연결 동작에 아무런 영향을 주지 않습니다. CONNECT 패킷과 JOIN 패킷은 대기 없이 연달아 전송됩니다. 기존 코드와의 호환을 위해 음수
         * 값은 여전히 {@link #build()}에서 거부됩니다.
         *
         * @param initialPacketDelayMs 대기 시간(밀리초), 기본값 1000
         * @return 빌더 인스턴스
         * @deprecated 효과가 없는 설정이며 향후 버전에서 제거될 예정입니다. CONNECT와 JOIN은 대기 없이 연달아 전송됩니다.
         */
        @Deprecated(forRemoval = true)
        public Builder initialPacketDelayMs(long initialPacketDelayMs) {
            this.initialPacketDelayMs = initialPacketDelayMs;
            return this;
        }

        /**
         * SOOPChatConfig 인스턴스를 생성합니다.
         *
         * @return 구성된 SOOPChatConfig 인스턴스
         */
        public SOOPChatConfig build() {
            if (bid == null || bid.isBlank()) {
                throw new IllegalArgumentException("bid must not be null or blank");
            }
            if (connectionTimeout.isNegative() || connectionTimeout.isZero()) {
                throw new IllegalArgumentException("connectionTimeout must be positive");
            }
            if (maxRetryAttempts < 0) {
                throw new IllegalArgumentException("maxRetryAttempts must be >= 0");
            }
            if (pingIntervalSeconds <= 0) {
                throw new IllegalArgumentException("pingIntervalSeconds must be positive");
            }
            if (initialPacketDelayMs < 0) {
                throw new IllegalArgumentException("initialPacketDelayMs must not be negative");
            }
            return new SOOPChatConfig(this);
        }
    }
}
