package com.github.getcurrentthread.soopapi.config;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Duration;

import org.junit.jupiter.api.Test;

class SOOPChatConfigTest {

    @Test
    void defaultBuild_succeeds() {
        SOOPChatConfig config = new SOOPChatConfig.Builder().bid("bj1").build();
        assertEquals("bj1", config.getBid());
        assertEquals(Duration.ofSeconds(30), config.getConnectionTimeout());
        assertEquals(5, config.getMaxRetryAttempts());
        assertEquals(60, config.getPingIntervalSeconds());
        assertFalse(config.isAuthenticated());
    }

    @Test
    void blankBid_throws() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new SOOPChatConfig.Builder().bid(" ").build());
    }

    @Test
    void customConnectionTimeout() {
        SOOPChatConfig config =
                new SOOPChatConfig.Builder()
                        .bid("bj1")
                        .connectionTimeout(Duration.ofSeconds(10))
                        .build();
        assertEquals(Duration.ofSeconds(10), config.getConnectionTimeout());
    }

    @Test
    void zeroConnectionTimeout_throws() {
        IllegalArgumentException e =
                assertThrows(
                        IllegalArgumentException.class,
                        () ->
                                new SOOPChatConfig.Builder()
                                        .bid("bj1")
                                        .connectionTimeout(Duration.ZERO)
                                        .build());
        assertEquals("connectionTimeout must be positive", e.getMessage());
    }

    @Test
    void negativeConnectionTimeout_throws() {
        IllegalArgumentException e =
                assertThrows(
                        IllegalArgumentException.class,
                        () ->
                                new SOOPChatConfig.Builder()
                                        .bid("bj1")
                                        .connectionTimeout(Duration.ofSeconds(-1))
                                        .build());
        assertEquals("connectionTimeout must be positive", e.getMessage());
    }

    @Test
    void nullConnectionTimeout_throws() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new SOOPChatConfig.Builder().connectionTimeout(null));
    }

    @Test
    @SuppressWarnings("removal")
    void deprecatedInitialPacketDelay_isStillAccepted() {
        SOOPChatConfig config =
                new SOOPChatConfig.Builder().bid("bj1").initialPacketDelayMs(5000).build();
        assertEquals(5000, config.getInitialPacketDelayMs());
    }

    @Test
    @SuppressWarnings("removal")
    void deprecatedInitialPacketDelay_negative_throws() {
        SOOPChatConfig.Builder builder =
                new SOOPChatConfig.Builder().bid("bj1").initialPacketDelayMs(-1);
        assertThrows(IllegalArgumentException.class, builder::build);
    }
}
