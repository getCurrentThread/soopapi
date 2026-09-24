package com.github.getcurrentthread.soopapi.config;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Duration;

import org.junit.jupiter.api.Test;

class SOOPClientConfigTest {

    @Test
    void defaultBuild_succeeds() {
        SOOPClientConfig config = new SOOPClientConfig.Builder().build();
        assertEquals(Duration.ofSeconds(15), config.getConnectionTimeout());
        assertEquals(5, config.getMaxRetryAttempts());
    }

    @Test
    void customConnectionTimeout() {
        SOOPClientConfig config =
                new SOOPClientConfig.Builder().connectionTimeout(Duration.ofSeconds(30)).build();
        assertEquals(Duration.ofSeconds(30), config.getConnectionTimeout());
    }

    @Test
    void negativeConnectionTimeout_throws() {
        IllegalArgumentException e =
                assertThrows(
                        IllegalArgumentException.class,
                        () ->
                                new SOOPClientConfig.Builder()
                                        .connectionTimeout(Duration.ofSeconds(-1))
                                        .build());
        assertEquals("connectionTimeout must be positive", e.getMessage());
    }

    @Test
    void zeroConnectionTimeout_throws() {
        IllegalArgumentException e =
                assertThrows(
                        IllegalArgumentException.class,
                        () -> new SOOPClientConfig.Builder().connectionTimeout(Duration.ZERO));
        assertEquals("connectionTimeout must be positive", e.getMessage());
    }

    @Test
    void nullConnectionTimeout_throws() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new SOOPClientConfig.Builder().connectionTimeout(null));
    }

    @Test
    void smallestPositiveConnectionTimeout_isValid() {
        SOOPClientConfig config =
                new SOOPClientConfig.Builder().connectionTimeout(Duration.ofMillis(1)).build();
        assertEquals(Duration.ofMillis(1), config.getConnectionTimeout());
    }

    @Test
    void maxRetryAttempts_zero_isValid() {
        SOOPClientConfig config = new SOOPClientConfig.Builder().maxRetryAttempts(0).build();
        assertEquals(0, config.getMaxRetryAttempts());
    }

    @Test
    void negativeMaxRetryAttempts_throws() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new SOOPClientConfig.Builder().maxRetryAttempts(-1));
    }

    @Test
    void customMaxRetryAttempts() {
        SOOPClientConfig config = new SOOPClientConfig.Builder().maxRetryAttempts(10).build();
        assertEquals(10, config.getMaxRetryAttempts());
    }
}
