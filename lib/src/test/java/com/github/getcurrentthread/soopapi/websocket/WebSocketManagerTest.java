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

import com.github.getcurrentthread.soopapi.api.model.AuthCookie;
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
        return manager(backoff, WebSocketManager.JOIN_RESEND_INTERVAL_MS);
    }

    private WebSocketManager manager(IntToLongFunction backoff, long joinResendMs) {
        SOOPChatConfig config =
                new SOOPChatConfig.Builder()
                        .bid("bj1")
                        .maxRetryAttempts(MAX_RETRIES)
                        .connectionTimeout(Duration.ofSeconds(2))
                        .build();
        SerialExecutor lane = new SerialExecutor(Runnable::run);
        MessageDispatcher dispatcher = new MessageDispatcher(Map.of(), lane, emitter);
        return new WebSocketManager(
                config, scheduler, lane, dispatcher, emitter, opener, backoff, joinResendMs);
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
    void connect_completesOnlyWhenServerAnswersJoin() throws Exception {
        opener.then(FakeWebSocket.Opener.Mode.SILENT);
        WebSocketManager mgr = manager();

        CompletableFuture<Void> connected = mgr.connect(CHANNEL);
        FakeWebSocket ws = opener.last();
        await(() -> ws.sent.size() == 2, "CONNECT and JOIN sent");

        assertFalse(connected.isDone(), "Sending JOIN is not joining");
        assertFalse(mgr.isConnected());

        ws.serverText(FakeWebSocket.JOIN_REPLY);

        connected.get(2, TimeUnit.SECONDS);
        assertTrue(mgr.isConnected());
        assertEquals(1, opener.attempts.get());
    }

    @Test
    void ignoredJoin_isResentOnTheSameSocketUntilAnswered() throws Exception {
        opener.then(FakeWebSocket.Opener.Mode.SILENT);
        WebSocketManager mgr = manager(attempt -> 0L, 20);

        CompletableFuture<Void> connected = mgr.connect(CHANNEL);
        FakeWebSocket ws = opener.last();
        await(() -> ws.joinSends() >= 3, "JOIN re-sent");
        ws.serverText(FakeWebSocket.JOIN_REPLY);
        connected.get(2, TimeUnit.SECONDS);

        long joinsAtReady = ws.joinSends();
        Thread.sleep(100);
        assertEquals(joinsAtReady, ws.joinSends(), "No JOIN is re-sent once joined");
        assertEquals(1, opener.attempts.get(), "Re-sending JOIN keeps the socket");
        assertEquals(0, ws.sendPendingViolations.get());
        assertTrue(reconnecting.isEmpty());
    }

    @Test
    void joinNeverAnswered_failsTheAttemptAndRetries() throws Exception {
        opener.then(FakeWebSocket.Opener.Mode.SILENT, FakeWebSocket.Opener.Mode.SUCCEED);
        WebSocketManager mgr = manager(attempt -> 0L, 50);

        mgr.connect(CHANNEL).get(5, TimeUnit.SECONDS);

        FakeWebSocket silent = opener.sockets.getFirst();
        assertTrue(silent.aborted, "The socket that never joined is dropped");
        assertEquals(2, opener.attempts.get());
        assertEquals(1, reconnecting.size());
        assertTrue(reconnected.isEmpty(), "A retried initial connect is not a reconnect");
        assertTrue(mgr.isConnected());
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
    void retryCount_resetsOnJoinReplyNotOnSend() throws Exception {
        opener.then(FakeWebSocket.Opener.Mode.SUCCEED, FakeWebSocket.Opener.Mode.SILENT);
        WebSocketManager mgr = manager();
        mgr.connect(CHANNEL).get(2, TimeUnit.SECONDS);
        opener.last().serverClose(1006, "");
        await(() -> opener.attempts.get() == 2, "retry opened");
        FakeWebSocket second = opener.last();
        await(() -> second.sent.size() == 2, "CONNECT and JOIN sent on retry");

        assertEquals(1, mgr.getStatus().get().retryCount(), "Sending is not proof of health");
        second.serverText("data");
        assertEquals(1, mgr.getStatus().get().retryCount(), "Other traffic is not a join");

        second.serverText(FakeWebSocket.JOIN_REPLY);
        await(() -> recovered(mgr), "recovery");
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

    /** 실패한 future의 원인. 성공했거나 아직 대기 중이면 테스트를 실패시킨다. */
    private static Throwable failureOf(CompletableFuture<?> future) {
        ExecutionException ex =
                assertThrows(ExecutionException.class, () -> future.get(2, TimeUnit.SECONDS));
        return ex.getCause();
    }

    private static boolean joined(CompletableFuture<Void> ready) {
        return ready.isDone() && !ready.isCompletedExceptionally();
    }

    @Test
    void ready_isPendingUntilJoinReplyThenCompleted() throws Exception {
        opener.then(FakeWebSocket.Opener.Mode.SILENT);
        WebSocketManager mgr = manager();
        CompletableFuture<Void> beforeConnect = mgr.ready();

        mgr.connect(CHANNEL);
        FakeWebSocket ws = opener.last();
        await(() -> ws.sent.size() == 2, "CONNECT and JOIN sent");
        CompletableFuture<Void> whileJoining = mgr.ready();
        whileJoining.complete(null);

        assertFalse(beforeConnect.isDone(), "Not ready before the server answers JOIN");
        assertFalse(mgr.ready().isDone(), "A caller completing its copy must not open the gate");

        ws.serverText(FakeWebSocket.JOIN_REPLY);

        beforeConnect.get(2, TimeUnit.SECONDS);
        assertTrue(joined(mgr.ready()), "Once joined, ready() is already completed");
    }

    @Test
    void ready_afterAbnormalDrop_waitsForTheNextJoin() throws Exception {
        opener.then(FakeWebSocket.Opener.Mode.SUCCEED, FakeWebSocket.Opener.Mode.SILENT);
        WebSocketManager mgr = manager();
        mgr.connect(CHANNEL).get(2, TimeUnit.SECONDS);
        CompletableFuture<Void> beforeDrop = mgr.ready();

        opener.last().serverClose(1006, "");
        CompletableFuture<Void> afterDrop = mgr.ready();

        assertTrue(joined(beforeDrop), "A ready() taken while joined stays completed");
        assertFalse(afterDrop.isDone(), "A dropped connection is not ready");
        await(() -> opener.attempts.get() == 2, "retry opened");
        FakeWebSocket second = opener.last();
        await(() -> second.sent.size() == 2, "CONNECT and JOIN sent on retry");
        assertFalse(afterDrop.isDone(), "Opening the retry socket is not joining");

        second.serverText(FakeWebSocket.JOIN_REPLY);

        afterDrop.get(2, TimeUnit.SECONDS);
        assertTrue(joined(mgr.ready()));
        assertFalse(mgr.terminated().isDone());
    }

    @Test
    void ready_dropBetweenJoinAndGateCompletion_leavesAPendingGate() throws Exception {
        opener.then(FakeWebSocket.Opener.Mode.SILENT, FakeWebSocket.Opener.Mode.SILENT);
        WebSocketManager mgr = manager();
        CompletableFuture<Void> firstJoin = mgr.ready();
        // onReady는 lock을 놓은 뒤 connect future를 준비 게이트보다 먼저 완료한다. 그 사이에 소켓을 끊어,
        // 게이트가 아직 대기 상태일 때 끊김이 처리되는 경우를 만든다.
        CompletableFuture<Void> dropped =
                mgr.connect(CHANNEL).thenRun(() -> opener.sockets.getFirst().serverClose(1006, ""));
        FakeWebSocket first = opener.last();
        await(() -> first.sent.size() == 2, "CONNECT and JOIN sent");

        first.serverText(FakeWebSocket.JOIN_REPLY);

        dropped.get(2, TimeUnit.SECONDS);
        firstJoin.get(2, TimeUnit.SECONDS);
        CompletableFuture<Void> next = mgr.ready();
        assertFalse(next.isDone(), "A socket dropped right after joining must not look ready");
        await(() -> opener.attempts.get() == 2, "retry opened");
        FakeWebSocket second = opener.last();
        await(() -> second.sent.size() == 2, "CONNECT and JOIN sent on retry");

        second.serverText(FakeWebSocket.JOIN_REPLY);

        next.get(2, TimeUnit.SECONDS);
    }

    @Test
    void ready_isResetByReconnect() throws Exception {
        opener.then(FakeWebSocket.Opener.Mode.SUCCEED, FakeWebSocket.Opener.Mode.SILENT);
        WebSocketManager mgr = manager();
        mgr.connect(CHANNEL).get(2, TimeUnit.SECONDS);
        assertTrue(joined(mgr.ready()));

        CompletableFuture<Void> reconnected = mgr.reconnect();
        CompletableFuture<Void> ready = mgr.ready();

        assertFalse(ready.isDone(), "ready() waits for the replacement socket to join");
        FakeWebSocket second = opener.last();
        await(() -> second.sent.size() == 2, "CONNECT and JOIN sent on the new socket");
        second.serverText(FakeWebSocket.JOIN_REPLY);

        ready.get(2, TimeUnit.SECONDS);
        reconnected.get(2, TimeUnit.SECONDS);
    }

    @Test
    void ready_failsOnClose() throws Exception {
        opener.then(FakeWebSocket.Opener.Mode.SILENT);
        WebSocketManager mgr = manager();
        mgr.connect(CHANNEL);
        CompletableFuture<Void> pending = mgr.ready();

        mgr.close();

        assertInstanceOf(ConnectionException.class, failureOf(pending));
        assertInstanceOf(ConnectionException.class, failureOf(mgr.ready()));
    }

    @Test
    void ready_afterCloseOfJoinedConnection_fails() throws Exception {
        WebSocketManager mgr = manager();
        mgr.connect(CHANNEL).get(2, TimeUnit.SECONDS);

        mgr.close();

        assertInstanceOf(ConnectionException.class, failureOf(mgr.ready()));
    }

    @Test
    void ready_failsWhenRetriesAreExhausted() throws Exception {
        opener.defaultMode = FakeWebSocket.Opener.Mode.FAIL;
        WebSocketManager mgr = manager();
        CompletableFuture<Void> pending = mgr.ready();

        mgr.connect(CHANNEL);

        assertInstanceOf(ConnectionException.class, failureOf(pending));
        assertInstanceOf(ConnectionException.class, failureOf(mgr.ready()));
    }

    @Test
    void ready_failsWhenTheServerClosesTheConnection() throws Exception {
        WebSocketManager mgr = manager();
        mgr.connect(CHANNEL).get(2, TimeUnit.SECONDS);

        opener.last().serverClose(4000, "kicked");

        Throwable cause = failureOf(mgr.ready());
        assertInstanceOf(ConnectionException.class, cause);
        assertTrue(cause.getMessage().contains("4000"));
    }

    /** 인증 연결의 JOIN 응답. 채팅 번호 1000, userFlag(synAck) "16|16384". */
    private static final String AUTH_JOIN_REPLY =
            "\u001b\t000200003000\u000c1000\u000cbj1\u000c0\u000c10\u000c\u000c0\u000c16|16384\u000c";

    private WebSocketManager authenticatedManager() {
        AuthCookie cookie =
                new AuthCookie(
                        "user1", true, "", "ticket0", null, null, null, null, null, "au0", null,
                        null, null);
        SOOPChatConfig config =
                new SOOPChatConfig.Builder()
                        .bid("bj1")
                        .maxRetryAttempts(MAX_RETRIES)
                        .connectionTimeout(Duration.ofSeconds(2))
                        .authCookie(cookie)
                        .build();
        SerialExecutor lane = new SerialExecutor(Runnable::run);
        MessageDispatcher dispatcher = new MessageDispatcher(Map.of(), lane, emitter);
        return new WebSocketManager(
                config,
                scheduler,
                lane,
                dispatcher,
                emitter,
                opener,
                attempt -> 0L,
                WebSocketManager.JOIN_RESEND_INTERVAL_MS);
    }

    @Test
    void enterInfo_isQueuedBeforeReadyWaitersCanSend() throws Exception {
        opener.then(FakeWebSocket.Opener.Mode.SILENT);
        WebSocketManager mgr = authenticatedManager();
        mgr.connect(CHANNEL);
        FakeWebSocket ws = opener.last();
        await(() -> ws.sent.size() == 2, "CONNECT and JOIN sent");
        // ready()는 수신 스레드에서 dispatcher보다 먼저 완료되므로, 곧바로 보낸 메시지가 ENTER_INFO를 앞지르면 안 된다.
        CompletableFuture<Void> chat = mgr.ready().thenCompose(unused -> mgr.sendChat("hi"));

        ws.serverText(AUTH_JOIN_REPLY);

        chat.get(2, TimeUnit.SECONDS);
        assertEquals(
                List.of(
                        WebSocketPacketBuilder.createEnterInfoPacket("16|16384"),
                        WebSocketPacketBuilder.createChatPacket("hi")),
                ws.sent.subList(2, ws.sent.size()));
    }

    @Test
    void enterInfo_followsEachJoinReplyOfThisChannel() throws Exception {
        opener.then(FakeWebSocket.Opener.Mode.SILENT);
        WebSocketManager mgr = authenticatedManager();
        mgr.connect(CHANNEL);
        FakeWebSocket ws = opener.last();
        await(() -> ws.sent.size() == 2, "CONNECT and JOIN sent");
        String enterInfo = WebSocketPacketBuilder.createEnterInfoPacket("16|16384");

        ws.serverText(AUTH_JOIN_REPLY);
        ws.serverText(AUTH_JOIN_REPLY.replace("\u000c1000\u000c", "\u000c2000\u000c"));
        ws.serverText(FakeWebSocket.JOIN_REPLY);
        ws.serverText(AUTH_JOIN_REPLY);

        assertEquals(
                List.of(enterInfo, enterInfo),
                ws.sent.subList(2, ws.sent.size()),
                "Another channel's reply and a reply without userFlag send nothing");
    }

    @Test
    void enterInfo_isNotSentOnAnonymousConnections() throws Exception {
        opener.then(FakeWebSocket.Opener.Mode.SILENT);
        WebSocketManager mgr = manager();
        mgr.connect(CHANNEL);
        FakeWebSocket ws = opener.last();
        await(() -> ws.sent.size() == 2, "CONNECT and JOIN sent");

        ws.serverText(AUTH_JOIN_REPLY);

        assertTrue(mgr.isConnected());
        assertEquals(List.of(connectPacket(), joinPacket()), ws.sent);
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
