package com.github.getcurrentthread.soopapi.connection;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.github.getcurrentthread.soopapi.client.SOOPChatClient;
import com.github.getcurrentthread.soopapi.config.SOOPChatConfig;
import com.github.getcurrentthread.soopapi.event.ChatEvent;
import com.github.getcurrentthread.soopapi.event.EventEmitter;
import com.github.getcurrentthread.soopapi.event.model.BaseEvent;
import com.github.getcurrentthread.soopapi.event.model.DisconnectedEvent;
import com.github.getcurrentthread.soopapi.exception.AdultBroadcastException;
import com.github.getcurrentthread.soopapi.exception.ConnectionException;
import com.github.getcurrentthread.soopapi.model.ChannelInfo;
import com.github.getcurrentthread.soopapi.model.ConnectionStatus;
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

    private static SOOPChatConfig config() {
        return new SOOPChatConfig.Builder()
                .bid("bj1")
                .connectionTimeout(Duration.ofSeconds(2))
                .build();
    }

    private SOOPConnection connection(SOOPConnection.ChannelLookup lookup) {
        return connection(new EventEmitter(), lookup);
    }

    private SOOPConnection connection(EventEmitter emitter, SOOPConnection.ChannelLookup lookup) {
        return new SOOPConnection(
                config(), scheduler, emitter, new SerialExecutor(Runnable::run), lookup);
    }

    /** SOOPLive가 19금 방송을 익명으로 조회했을 때 내는 예외. */
    private static AdultBroadcastException adultBroadcast() {
        return new AdultBroadcastException(
                "The broadcast of bj1 is 19+ and cannot be joined anonymously. Sign in with an"
                        + " age-verified account and pass its AuthCookie.");
    }

    /** 실제 조회처럼 thenApply 안에서 던진 예외가 CompletionException에 싸여 오는 실패. */
    private static CompletableFuture<ChannelInfo> failedLookup(Throwable error) {
        return CompletableFuture.failedFuture(new CompletionException(error));
    }

    /** 실패 원인이 {@code expected}를 원인으로 둔 ConnectionException이고, 그 메시지에 안내 문구가 담겼는지 확인한다. */
    private static void assertCausedBy(Throwable expected, Throwable failure) {
        ConnectionException ce = assertInstanceOf(ConnectionException.class, failure);
        assertSame(expected, ce.getCause());
        assertTrue(ce.getMessage().contains(expected.getMessage()), ce.getMessage());
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
    void adultBroadcast_failsEveryFutureWithTheTypedCauseAndDoesNotRetry() throws Exception {
        AdultBroadcastException adult = adultBroadcast();
        AtomicInteger lookups = new AtomicInteger();
        List<BaseEvent> reconnecting = new CopyOnWriteArrayList<>();
        EventEmitter emitter = new EventEmitter();
        emitter.on(ChatEvent.RECONNECTING, reconnecting::add);
        SOOPConnection connection =
                connection(
                        emitter,
                        config -> {
                            lookups.incrementAndGet();
                            return failedLookup(adult);
                        });
        CompletableFuture<Void> readyBeforeConnect = connection.ready();

        CompletableFuture<Void> connected = connection.connect();

        assertCausedBy(adult, failureOf(connected));
        assertCausedBy(adult, failureOf(readyBeforeConnect));
        assertCausedBy(adult, failureOf(connection.ready()));
        assertCausedBy(adult, failureOf(connection.terminated()));
        assertCausedBy(adult, failureOf(connection.connect()));
        // 조회에서 끝났으므로 소켓을 연 적도, backoff 재시도를 예약한 적도 없다.
        assertNull(connection.getChannelInfo(), "The WebSocket is never opened");
        assertEquals(1, lookups.get(), "The lookup is not retried");
        assertTrue(scheduler.getQueue().isEmpty(), "No retry is scheduled");
        assertTrue(reconnecting.isEmpty(), "No RECONNECTING");
        ConnectionStatus status = connection.status().get(2, TimeUnit.SECONDS);
        assertFalse(status.reconnecting());
        assertEquals(0, status.retryCount());
    }

    @Test
    void adultBroadcast_endsTheClientSessionWithOneErrorDisconnect() throws Exception {
        AdultBroadcastException adult = adultBroadcast();
        AtomicInteger lookups = new AtomicInteger();
        ChatConnectionFactory factory =
                new ChatConnectionFactory() {
                    @Override
                    public ChatConnection createConnection(
                            SOOPChatConfig config, EventEmitter emitter, SerialExecutor lane) {
                        return new SOOPConnection(
                                config,
                                scheduler,
                                emitter,
                                lane,
                                c -> {
                                    lookups.incrementAndGet();
                                    return failedLookup(adult);
                                });
                    }

                    @Override
                    public SerialExecutor newLane() {
                        return new SerialExecutor(Runnable::run);
                    }
                };
        List<DisconnectedEvent> disconnects = new CopyOnWriteArrayList<>();
        List<BaseEvent> reconnecting = new CopyOnWriteArrayList<>();

        try (SOOPChatClient client = new SOOPChatClient(config(), factory)) {
            client.on(ChatEvent.DISCONNECTED, (DisconnectedEvent e) -> disconnects.add(e));
            client.on(ChatEvent.RECONNECTING, reconnecting::add);

            CompletableFuture<Void> session = client.connectToChat();

            assertCausedBy(adult, failureOf(session));
            assertEquals(1, disconnects.size(), "One DISCONNECTED per session");
            DisconnectedEvent disconnected = disconnects.getFirst();
            assertTrue(disconnected.causedByError());
            assertTrue(disconnected.reason().contains(adult.getMessage()), disconnected.reason());
            assertTrue(reconnecting.isEmpty(), "No RECONNECTING");
            assertEquals(1, lookups.get(), "The lookup is not retried");
            assertFalse(client.isConnected());
        }
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
