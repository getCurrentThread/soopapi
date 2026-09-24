package com.github.getcurrentthread.soopapi.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.http.HttpResponse;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

import org.junit.jupiter.api.Test;

import com.github.getcurrentthread.soopapi.api.model.StationInfo;
import com.github.getcurrentthread.soopapi.exception.SOOPChatException;

class SOOPChannelTest {

    // 실제 응답과 같은 구조의 합성 본문: 방송국 필드는 station 아래, 팬 수는 station.upd.fan_cnt
    private static final String STATION =
            "\"station\":{\"user_id\":\"bj1\",\"user_nick\":\"nick1\",\"station_no\":123,"
                    + "\"station_name\":\"name1\",\"station_title\":\"title1\","
                    + "\"upd\":{\"fan_cnt\":4567,\"user_id\":\"bj1\"}}";

    @Test
    void parseStation_live() {
        StationInfo info =
                SOOPChannel.parseStation(
                        "{" + STATION + ",\"broad\":{\"broad_no\":1},\"is_owner\":false}", "bj1");

        assertEquals("bj1", info.userId());
        assertEquals("nick1", info.userNickname());
        assertEquals(123L, info.stationNo());
        assertEquals("name1", info.stationName());
        assertEquals("title1", info.stationTitle());
        assertTrue(info.isLive());
        assertEquals(4567, info.totalFollowers());
    }

    @Test
    void parseStation_offlineWhenBroadIsNull() {
        StationInfo info = SOOPChannel.parseStation("{" + STATION + ",\"broad\":null}", "bj1");

        assertFalse(info.isLive());
        assertEquals("name1", info.stationName());
        assertEquals(4567, info.totalFollowers());
    }

    @Test
    void parseStation_offlineWhenBroadIsAbsent() {
        assertFalse(SOOPChannel.parseStation("{" + STATION + "}", "bj1").isLive());
    }

    @Test
    void parseStation_missingOptionalFieldsFallBackToDefaults() {
        StationInfo info = SOOPChannel.parseStation("{\"station\":{},\"broad\":null}", "bj1");

        assertEquals("bj1", info.userId());
        assertEquals("", info.userNickname());
        assertEquals(0L, info.stationNo());
        assertEquals(0, info.totalFollowers());
    }

    @Test
    void parseStation_missingStationThrows() {
        SOOPChatException e =
                assertThrows(
                        SOOPChatException.class,
                        () -> SOOPChannel.parseStation("{\"code\":9000,\"message\":\"x\"}", "bj1"));
        assertNull(e.getCause());
    }

    @Test
    void parseStation_invalidJsonThrows() {
        SOOPChatException e =
                assertThrows(
                        SOOPChatException.class, () -> SOOPChannel.parseStation("not json", "bj1"));
        assertEquals("Failed to parse station info", e.getMessage());
    }

    @Test
    void station_missingStationIsNotRewrapped() {
        try (SOOPHttpClient http = stubGet(200, "{\"code\":9000,\"message\":\"x\"}")) {
            CompletionException ex =
                    assertThrows(
                            CompletionException.class,
                            () -> new SOOPChannel(http).station("bj1").join());
            SOOPChatException cause = assertInstanceOf(SOOPChatException.class, ex.getCause());
            assertEquals("Station info missing in response", cause.getMessage());
        }
    }

    @Test
    void station_non200Throws() {
        try (SOOPHttpClient http = stubGet(515, "{\"code\":9000,\"message\":\"x\"}")) {
            CompletionException ex =
                    assertThrows(
                            CompletionException.class,
                            () -> new SOOPChannel(http).station("bj1").join());
            assertInstanceOf(SOOPChatException.class, ex.getCause());
        }
    }

    private static SOOPHttpClient stubGet(int status, String body) {
        return new SOOPHttpClient() {
            @Override
            public CompletableFuture<HttpResponse<String>> get(String url) {
                return CompletableFuture.completedFuture(new StubHttpResponse(status, body));
            }
        };
    }
}
