package com.github.getcurrentthread.soopapi.websocket;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.function.IntToLongFunction;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.github.getcurrentthread.soopapi.config.SOOPChatConfig;
import com.github.getcurrentthread.soopapi.decoder.MessageDispatcher;
import com.github.getcurrentthread.soopapi.event.ChatEvent;
import com.github.getcurrentthread.soopapi.event.EventEmitter;
import com.github.getcurrentthread.soopapi.event.model.DisconnectedEvent;
import com.github.getcurrentthread.soopapi.event.model.RawEvent;
import com.github.getcurrentthread.soopapi.event.model.ReconnectedEvent;
import com.github.getcurrentthread.soopapi.event.model.ReconnectingEvent;
import com.github.getcurrentthread.soopapi.exception.ConnectionException;
import com.github.getcurrentthread.soopapi.model.ChannelInfo;
import com.github.getcurrentthread.soopapi.util.SerialExecutor;

class WebSocketManagerTest {

    private static final int MAX_RETRIES = 3;
    private static final ChannelInfo CHANNEL =
            new ChannelInfo("chat.example.test", "1000", "ftk0", "title", "bj1", "8001");

    private ScheduledThreadPoolExecutor scheduler;
    private EventEmitter emitter;
    private FakeWebSocket.Opener opener;
    private final List<ReconnectingEvent> reconnecting = new CopyOnWriteArrayList<>();
    private final List<ReconnectedEvent> reconnected = new CopyOnWriteArrayList<>();
    private final List<String> raws = new CopyOnWriteArrayList<>();

    @BeforeEach
    void setup() {
        scheduler = new ScheduledThreadPoolExecutor(1);
        emitter = new EventEmitter();
        opener = new FakeWebSocket.Opener();
        emitter.on(ChatEvent.RECONNECTING, (ReconnectingEvent e) -> reconnecting.add(e));
        emitter.on(ChatEvent.RECONNECTED, (ReconnectedEvent e) -> reconnected.add(e));
        emitter.on(ChatEvent.RAW, (RawEvent e) -> raws.add(e.raw()));
    }

    @AfterEach
    void tearDown() {
        scheduler.shutdownNow();
    }

    private WebSocketManager manager(IntToLongFunction backoff) {
        SOOPChatConfig config =
                new SOOPChatConfig.Builder()
                        .bid("bj1")
                        .maxRetryAttempts(MAX_RETRIES)
                        .connectionTimeout(Duration.ofSeconds(2))
                        .build();
        SerialExecutor lane = new SerialExecutor(Runnable::run);
        MessageDispatcher dispatcher = new MessageDispatcher(Map.of(), lane, emitter);
        return new WebSocketManager(config, scheduler, lane, dispatcher, emitter, opener, backoff);
    }

    private WebSocketManager manager() {
        return manager(attempt -> 0L);
    }

    private static void await(BooleanSupplier condition, String message) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) {
                fail("Timed out waiting: " + message);
            }
            Thread.sleep(5);
        }
    }

    /** 두 번째 소켓이 수립되고 RECONNECTED까지 전달됐는지. */
    private boolean recovered(WebSocketManager mgr) {
        return opener.attempts.get() == 2 && mgr.isConnected() && reconnected.size() == 1;
    }

    private static String connectPacket() {
        return WebSocketPacketBuilder.createConnectPacket(null);
    }

    private static String joinPacket() {
        return WebSocketPacketBuilder.createJoinPacket(CHANNEL, null, null);
    }

    @Test
    void connect_sendsConnectThenJoinAndCompletes() throws Exception {
        WebSocketManager mgr = manager();

        mgr.connect(CHANNEL).get(2, TimeUnit.SECONDS);

        assertTrue(mgr.isConnected());
        assertEquals(List.of(connectPacket(), joinPacket()), opener.last().sent);
        assertTrue(reconnecting.isEmpty());
        assertTrue(reconnected.isEmpty(), "Initial connect is not a reconnect");
    }

    @Test
    void invalidMessage_failsWithoutTouchingTheSocket() throws Exception {
        WebSocketManager mgr = manager();
        mgr.connect(CHANNEL).get(2, TimeUnit.SECONDS);

        ExecutionException ex =
                assertThrows(
                        ExecutionException.class,
                        () -> mgr.sendChat("a\u000cb").get(1, TimeUnit.SECONDS));

        assertInstanceOf(IllegalArgumentException.class, ex.getCause());
        assertEquals(2, opener.last().sent.size(), "Only CONNECT and JOIN were sent");
        assertTrue(mgr.isConnected(), "A rejected message is not a transport failure");
    }

    @Test
    void concurrentSends_areSerializedBehindHandshake() throws Exception {
        opener.manualSends = true;
        WebSocketManager mgr = manager();
        CompletableFuture<Void> connected = mgr.connect(CHANNEL);
        FakeWebSocket ws = opener.last();

        int threads = 4;
        int perThread = 5;
        List<CompletableFuture<Void>> sends = new CopyOnWriteArrayList<>();
        CountDownLatch start = new CountDownLatch(1);
        try (ExecutorService pool = Executors.newFixedThreadPool(threads)) {
            for (int t = 0; t < threads; t++) {
                int thread = t;
                pool.execute(
                        () -> {
                            try {
                                start.await();
                            } catch (InterruptedException e) {
                                Thread.currentThread().interrupt();
                            }
                            for (int i = 0; i < perThread; i++) {
                                sends.add(mgr.sendChat("t" + thread + "-" + i));
                            }
                        });
            }
            start.countDown();
        }

        int guard = 0;
        while (ws.completeSend() && guard++ < 100) {
            // 진행 중인 송신을 하나씩 완료한다. 완료가 다음 고리의 송신을 시작시킨다.
        }

        connected.get(2, TimeUnit.SECONDS);
        CompletableFuture.allOf(sends.toArray(CompletableFuture[]::new)).get(2, TimeUnit.SECONDS);
        assertEquals(0, ws.sendPendingViolations.get(), "No send may overlap another");
        assertEquals(2 + threads * perThread, ws.sent.size());
        assertEquals(connectPacket(), ws.sent.get(0), "CONNECT goes first");
        assertEquals(joinPacket(), ws.sent.get(1), "JOIN goes second");
    }

    @Test
    void sendFailure_abortsOldSocketAndReconnectsOnce() throws Exception {
        WebSocketManager mgr = manager();
        mgr.connect(CHANNEL).get(2, TimeUnit.SECONDS);
        FakeWebSocket first = opener.last();

        first.failNextSend = new IOException("broken pipe");
        assertThrows(
                ExecutionException.class, () -> mgr.sendChat("hello").get(2, TimeUnit.SECONDS));

        await(() -> recovered(mgr), "recovery");
        assertTrue(first.aborted, "The failed socket must be aborted");
        assertEquals(1, reconnecting.size());
        assertEquals(1, reconnected.size());

        first.serverText("stale data");
        assertFalse(raws.contains("stale data"), "A retired socket must not deliver data");
    }

    @Test
    void pingFailure_triggersRecoveryWithoutBlocking() throws Exception {
        WebSocketManager mgr = manager();
        mgr.connect(CHANNEL).get(2, TimeUnit.SECONDS);
        FakeWebSocket first = opener.last();

        first.failNextSend = new IOException("ping failed");
        mgr.pingNow();

        await(() -> recovered(mgr), "recovery after ping");
        assertTrue(first.aborted);
        assertEquals(WebSocketPacketBuilder.createPingPacket(), first.sent.getLast());
        assertEquals(1, reconnecting.size());
        assertEquals(1, reconnected.size());
    }

    @Test
    void staleSocketCallbacks_areIgnored() throws Exception {
        WebSocketManager mgr = manager();
        mgr.connect(CHANNEL).get(2, TimeUnit.SECONDS);
        FakeWebSocket first = opener.last();
        first.serverClose(1006, "");
        await(() -> recovered(mgr), "recovery");

        first.serverClose(1000, "late close");
        first.serverError(new IOException("late error"));

        assertTrue(mgr.isConnected(), "Callbacks of a replaced socket must not affect state");
        assertFalse(mgr.terminated().isDone());
        assertEquals(2, opener.attempts.get());
    }

    @Test
    void serverCloseFrame_endsConnectionWithoutRetry() throws Exception {
        WebSocketManager mgr = manager();
        mgr.connect(CHANNEL).get(2, TimeUnit.SECONDS);

        opener.last().serverClose(4000, "kicked");

        DisconnectedEvent event = mgr.terminated().get(2, TimeUnit.SECONDS);
        assertEquals(4000, event.statusCode());
        assertEquals("kicked", event.reason());
        assertFalse(event.causedByError());
        assertFalse(event.isClientInitiated());
        assertFalse(mgr.isConnected());
        Thread.sleep(50);
        assertEquals(1, opener.attempts.get(), "A server close must not be retried");
        assertTrue(reconnecting.isEmpty());
        assertThrows(ExecutionException.class, () -> mgr.sendChat("x").get(1, TimeUnit.SECONDS));
        assertThrows(ExecutionException.class, () -> mgr.reconnect().get(1, TimeUnit.SECONDS));
    }

    @Test
    void abnormalClose1006_reconnects() throws Exception {
        WebSocketManager mgr = manager();
        mgr.connect(CHANNEL).get(2, TimeUnit.SECONDS);

        opener.last().serverClose(1006, "");

        await(() -> recovered(mgr), "recovery after 1006");
        assertFalse(mgr.terminated().isDone());
        assertEquals(1, reconnecting.size());
        assertEquals(1, reconnected.size());
    }

    @Test
    void transportError_reconnects() throws Exception {
        WebSocketManager mgr = manager();
        mgr.connect(CHANNEL).get(2, TimeUnit.SECONDS);

        opener.last().serverError(new IOException("connection reset"));

        await(() -> recovered(mgr), "recovery after error");
        assertFalse(mgr.terminated().isDone());
        assertEquals(1, reconnected.size());
    }

    @Test
    void serverThatDropsEveryConnection_exhaustsRetriesAndTerminatesOnce() throws Exception {
        opener.defaultMode = FakeWebSocket.Opener.Mode.OPEN_THEN_DROP;
        WebSocketManager mgr = manager();
        AtomicInteger terminations = new AtomicInteger();
        CompletableFuture<DisconnectedEvent> terminated = mgr.terminated();
        terminated.whenComplete((e, ex) -> terminations.incrementAndGet());

        mgr.connect(CHANNEL);

        ExecutionException ex =
                assertThrows(ExecutionException.class, () -> terminated.get(5, TimeUnit.SECONDS));
        assertInstanceOf(ConnectionException.class, ex.getCause());
        Thread.sleep(100);
        assertEquals(1, terminations.get());
        assertEquals(MAX_RETRIES + 1, opener.attempts.get());
        assertEquals(MAX_RETRIES, reconnecting.size());
        assertFalse(mgr.isConnected());

        // 소진 뒤의 호출은 멈추지 않고 곧바로 실패한다.
        assertThrows(ExecutionException.class, () -> mgr.reconnect().get(1, TimeUnit.SECONDS));
        assertThrows(ExecutionException.class, () -> mgr.connect(CHANNEL).get(1, TimeUnit.SECONDS));
    }

    @Test
    void initialConnectFailures_exhaustAndFailTheConnectFuture() throws Exception {
        opener.defaultMode = FakeWebSocket.Opener.Mode.FAIL;
        WebSocketManager mgr = manager();

        ExecutionException ex =
                assertThrows(
                        ExecutionException.class,
                        () -> mgr.connect(CHANNEL).get(5, TimeUnit.SECONDS));

        assertInstanceOf(ConnectionException.class, ex.getCause());
        assertEquals(MAX_RETRIES + 1, opener.attempts.get());
        assertTrue(mgr.terminated().isCompletedExceptionally());
    }

    @Test
    void closeDuringBackoff_cancelsRetry() throws Exception {
        opener.then(FakeWebSocket.Opener.Mode.FAIL);
        WebSocketManager mgr = manager(attempt -> 60_000L);

        CompletableFuture<Void> connected = mgr.connect(CHANNEL);
        await(() -> reconnecting.size() == 1, "first retry scheduled");

        mgr.close();

        assertThrows(ExecutionException.class, () -> connected.get(1, TimeUnit.SECONDS));
        DisconnectedEvent event = mgr.terminated().get(1, TimeUnit.SECONDS);
        assertTrue(event.isClientInitiated());
        await(
                () -> scheduler.getQueue().stream().allMatch(f -> ((Future<?>) f).isCancelled()),
                "retry cancelled");
        assertEquals(1, opener.attempts.get());
    }

    @Test
    void initialConnectSucceedingOnRetry_emitsNoReconnected() throws Exception {
        opener.then(FakeWebSocket.Opener.Mode.FAIL, FakeWebSocket.Opener.Mode.SUCCEED);
        WebSocketManager mgr = manager();

        mgr.connect(CHANNEL).get(2, TimeUnit.SECONDS);

        assertEquals(1, reconnecting.size());
        assertEquals(1, reconnecting.getFirst().attemptNumber());
        assertTrue(reconnected.isEmpty(), "A retried initial connect is not a reconnect");
    }

    @Test
    void openerThrowing_countsAsFailedAttempt() throws Exception {
        opener.then(FakeWebSocket.Opener.Mode.THROW, FakeWebSocket.Opener.Mode.SUCCEED);
        WebSocketManager mgr = manager();

        mgr.connect(CHANNEL).get(2, TimeUnit.SECONDS);

        assertEquals(2, opener.attempts.get());
        assertTrue(mgr.isConnected());
    }

    @Test
    void callbacksBeforeOpenCompletes_areHandled() throws Exception {
        opener.then(FakeWebSocket.Opener.Mode.OPEN_EARLY);
        WebSocketManager mgr = manager();

        CompletableFuture<Void> connected = mgr.connect(CHANNEL);
        assertFalse(connected.isDone());
        opener.pending.getFirst().complete(opener.last());

        connected.get(2, TimeUnit.SECONDS);
        assertTrue(raws.contains("early"), "Frames that arrive before open completes are kept");
        assertTrue(mgr.isConnected());
    }

    @Test
    void socketOpeningAfterClose_isAborted() throws Exception {
        opener.then(FakeWebSocket.Opener.Mode.MANUAL);
        WebSocketManager mgr = manager();
        CompletableFuture<Void> connected = mgr.connect(CHANNEL);

        mgr.close();
        FakeWebSocket late = opener.last();
        opener.pending.getFirst().complete(late);

        assertTrue(late.aborted, "A socket that opens after close() must be aborted");
        assertTrue(late.sent.isEmpty());
        assertThrows(ExecutionException.class, () -> connected.get(1, TimeUnit.SECONDS));
    }

    @Test
    void retryCount_resetsOnFirstInboundFrameNotOnSend() throws Exception {
        WebSocketManager mgr = manager();
        mgr.connect(CHANNEL).get(2, TimeUnit.SECONDS);
        opener.last().serverClose(1006, "");
        await(() -> recovered(mgr), "recovery");

        mgr.sendChat("hello").get(2, TimeUnit.SECONDS);
        assertEquals(1, mgr.getStatus().get().retryCount(), "A send is not proof of health");

        opener.last().serverText("data");
        assertEquals(0, mgr.getStatus().get().retryCount());
    }

    @Test
    void reconnect_replacesSocketAndEmitsOnePair() throws Exception {
        WebSocketManager mgr = manager();
        mgr.connect(CHANNEL).get(2, TimeUnit.SECONDS);
        FakeWebSocket first = opener.last();

        mgr.reconnect().get(2, TimeUnit.SECONDS);

        assertEquals(2, opener.attempts.get());
        assertEquals(1, first.closeCalls.get(), "The replaced socket is closed gracefully");
        assertTrue(first.aborted);
        assertEquals(1, reconnecting.size());
        assertEquals(1, reconnected.size());
        assertTrue(mgr.isConnected());
    }

    @Test
    void close_isIdempotent() throws Exception {
        WebSocketManager mgr = manager();
        mgr.connect(CHANNEL).get(2, TimeUnit.SECONDS);
        FakeWebSocket ws = opener.last();
        List<DisconnectedEvent> ends = new ArrayList<>();
        mgr.terminated().thenAccept(ends::add);

        mgr.close();
        mgr.close();

        assertEquals(1, ws.closeCalls.get());
        await(() -> ws.aborted, "abort after close handshake");
        assertEquals(1, ends.size());
        assertTrue(ends.getFirst().isClientInitiated());
        assertFalse(mgr.isConnected());
        assertThrows(ExecutionException.class, () -> mgr.sendChat("x").get(1, TimeUnit.SECONDS));
    }

    @Test
    void backoff_isExponentialAndCapped() {
        assertEquals(2_000, WebSocketManager.backoffMillis(1));
        assertEquals(4_000, WebSocketManager.backoffMillis(2));
        assertEquals(16_000, WebSocketManager.backoffMillis(4));
        assertEquals(30_000, WebSocketManager.backoffMillis(5));
        assertEquals(30_000, WebSocketManager.backoffMillis(100));
        assertEquals(30_000, WebSocketManager.backoffMillis(Integer.MAX_VALUE));
    }
}
