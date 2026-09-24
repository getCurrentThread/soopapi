package com.github.getcurrentthread.soopapi.connection;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.github.getcurrentthread.soopapi.config.SOOPChatConfig;
import com.github.getcurrentthread.soopapi.event.EventEmitter;
import com.github.getcurrentthread.soopapi.exception.ConnectionException;
import com.github.getcurrentthread.soopapi.model.ChannelInfo;
import com.github.getcurrentthread.soopapi.util.SerialExecutor;

/** 방송 정보 조회를 주입해, WebSocket을 열기 전에 끝나는 연결을 네트워크 없이 검증한다. */
class SOOPConnectionTest {

    private ScheduledThreadPoolExecutor scheduler;

    @BeforeEach
    void setup() {
        scheduler = new ScheduledThreadPoolExecutor(1);
    }

    @AfterEach
    void tearDown() {
        scheduler.shutdownNow();
    }

    private SOOPConnection connection(SOOPConnection.ChannelLookup lookup) {
        SOOPChatConfig config =
                new SOOPChatConfig.Builder()
                        .bid("bj1")
                        .connectionTimeout(Duration.ofSeconds(2))
                        .build();
        return new SOOPConnection(
                config, scheduler, new EventEmitter(), new SerialExecutor(Runnable::run), lookup);
    }

    /** 실패한 future의 원인. 성공했거나 아직 대기 중이면 테스트를 실패시킨다. */
    private static Throwable failureOf(CompletableFuture<?> future) {
        ExecutionException ex =
                assertThrows(ExecutionException.class, () -> future.get(2, TimeUnit.SECONDS));
        return ex.getCause();
    }

    @Test
    void ready_failsWithTheLookupFailure() {
        CompletableFuture<ChannelInfo> lookup = new CompletableFuture<>();
        SOOPConnection connection = connection(config -> lookup);
        CompletableFuture<Void> readyBeforeConnect = connection.ready();
        CompletableFuture<Void> connected = connection.connect();
        CompletableFuture<Void> readyWhileLookingUp = connection.ready();
        assertFalse(readyBeforeConnect.isDone());
        assertFalse(readyWhileLookingUp.isDone());

        ConnectionException offline = new ConnectionException("Streamer is not live");
        lookup.completeExceptionally(offline);

        assertSame(offline, failureOf(connected));
        assertSame(offline, failureOf(readyBeforeConnect), "Fails with the lookup failure");
        assertSame(offline, failureOf(readyWhileLookingUp));
        assertSame(offline, failureOf(connection.ready()), "Later calls fail the same way");
        assertSame(offline, failureOf(connection.terminated()));
    }

    @Test
    void ready_failsWhenTheChannelInfoIsInvalid() {
        ChannelInfo noPort =
                new ChannelInfo("chat.example.test", "1000", "ftk0", "title", "bj1", "");
        SOOPConnection connection = connection(config -> CompletableFuture.completedFuture(noPort));
        CompletableFuture<Void> ready = connection.ready();

        connection.connect();

        Throwable cause = failureOf(ready);
        assertInstanceOf(ConnectionException.class, cause);
        assertTrue(cause.getMessage().contains("Invalid channel port"), cause.getMessage());
    }

    @Test
    void ready_failsWhenClosedDuringTheLookup() {
        SOOPConnection connection = connection(config -> new CompletableFuture<>());
        connection.connect();
        CompletableFuture<Void> ready = connection.ready();

        connection.close();

        assertInstanceOf(ConnectionException.class, failureOf(ready));
        assertFalse(connection.terminated().isCompletedExceptionally(), "Closing is not a failure");
    }
}
