package com.github.getcurrentthread.soopapi.client;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.github.getcurrentthread.soopapi.config.SOOPChatConfig;
import com.github.getcurrentthread.soopapi.connection.FakeChatConnection;
import com.github.getcurrentthread.soopapi.connection.FakeConnectionFactory;
import com.github.getcurrentthread.soopapi.event.ChatEvent;
import com.github.getcurrentthread.soopapi.event.model.BaseEvent;
import com.github.getcurrentthread.soopapi.event.model.DisconnectedEvent;
import com.github.getcurrentthread.soopapi.event.model.RawEvent;
import com.github.getcurrentthread.soopapi.event.model.ReconnectingEvent;
import com.github.getcurrentthread.soopapi.exception.ConnectionException;

class SOOPChatClientSessionTest {

    private FakeConnectionFactory factory;
    private SOOPChatClient client;
    private final List<DisconnectedEvent> disconnects = new CopyOnWriteArrayList<>();
    private final List<BaseEvent> lifecycle = new CopyOnWriteArrayList<>();

    @BeforeEach
    void setup() {
        factory = FakeConnectionFactory.async();
        client = newClient(factory);
    }

    private SOOPChatClient newClient(FakeConnectionFactory f) {
        SOOPChatClient c = new SOOPChatClient(new SOOPChatConfig.Builder().bid("bj1").build(), f);
        c.on(
                ChatEvent.DISCONNECTED,
                (DisconnectedEvent e) -> {
                    disconnects.add(e);
                    lifecycle.add(e);
                });
        c.on(ChatEvent.RECONNECTING, lifecycle::add);
        c.on(ChatEvent.RECONNECTED, lifecycle::add);
        return c;
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

    private static RawEvent raw(String text) {
        return new RawEvent(ChatEvent.RAW, text, System.currentTimeMillis());
    }

    @Test
    void connectToChat_returnsSameFutureWhileSessionRuns() {
        CompletableFuture<Void> first = client.connectToChat();
        CompletableFuture<Void> second = client.connectToChat();

        assertSame(first, second);
        assertEquals(1, factory.created.size());
    }

    @Test
    void disconnectWhileConnecting_endsSessionWithOneClientDisconnect() throws Exception {
        CompletableFuture<Void> session = client.connectToChat();
        FakeChatConnection connection = factory.last();

        client.disconnect();

        session.get(2, TimeUnit.SECONDS);
        assertTrue(connection.isClosed());
        await(() -> disconnects.size() == 1, "DISCONNECTED");
        assertTrue(disconnects.getFirst().isClientInitiated());
        assertFalse(client.isConnected());
    }

    @Test
    void lateCompletionOfEndedConnection_isIgnored() throws Exception {
        CompletableFuture<Void> session = client.connectToChat();
        FakeChatConnection connection = factory.last();
        client.disconnect();
        session.get(2, TimeUnit.SECONDS);

        connection.establish();
        connection.serverClose(1000, "late");
        Thread.sleep(50);

        assertEquals(1, disconnects.size(), "An ended connection cannot end a session twice");
        assertFalse(client.isConnected());
    }

    @Test
    void serverClose_endsSessionNormallyWithOneDisconnect() throws Exception {
        CompletableFuture<Void> session = client.connectToChat();
        FakeChatConnection connection = factory.last();
        connection.establish();
        assertTrue(client.isConnected());

        connection.serverClose(4000, "kicked");

        session.get(2, TimeUnit.SECONDS);
        await(() -> disconnects.size() == 1, "DISCONNECTED");
        assertEquals(4000, disconnects.getFirst().statusCode());
        assertFalse(disconnects.getFirst().causedByError());
        Thread.sleep(50);
        assertEquals(1, disconnects.size());
    }

    @Test
    void connectionFailure_endsSessionExceptionallyWithErrorDisconnect() throws Exception {
        CompletableFuture<Void> session = client.connectToChat();

        factory.last().fail("retries exhausted");

        ExecutionException ex =
                assertThrows(ExecutionException.class, () -> session.get(2, TimeUnit.SECONDS));
        assertInstanceOf(ConnectionException.class, ex.getCause());
        await(() -> disconnects.size() == 1, "DISCONNECTED");
        assertTrue(disconnects.getFirst().causedByError());
    }

    @Test
    void disconnectedEvent_precedesSessionCompletion() throws Exception {
        AtomicInteger disconnectsSeenAtCompletion = new AtomicInteger(-1);
        CompletableFuture<Void> session = client.connectToChat();
        CompletableFuture<Void> observed =
                session.whenComplete((v, e) -> disconnectsSeenAtCompletion.set(disconnects.size()));

        factory.last().establish();
        factory.last().serverClose(1000, "bye");

        observed.get(2, TimeUnit.SECONDS);
        assertEquals(1, disconnectsSeenAtCompletion.get());
    }

    @Test
    void forceReconnect_keepsSessionAndEmitsNoDisconnect() throws Exception {
        CompletableFuture<Void> session = client.connectToChat();
        FakeChatConnection old = factory.last();
        old.establish();

        CompletableFuture<Void> forced = client.forceReconnect();
        FakeChatConnection fresh = factory.last();
        fresh.establish();

        assertSame(session, forced, "forceReconnect keeps the same session future");
        assertTrue(old.isClosed());
        await(() -> lifecycle.size() == 2, "RECONNECTING and RECONNECTED");
        ReconnectingEvent reconnecting =
                assertInstanceOf(ReconnectingEvent.class, lifecycle.get(0));
        assertEquals(1, reconnecting.attemptNumber());
        assertEquals(1, reconnecting.maxAttempts());
        assertEquals(0L, reconnecting.delayMs());
        assertEquals(ChatEvent.RECONNECTED, lifecycle.get(1).eventType());
        assertTrue(
                disconnects.isEmpty(), "Closing the replaced connection must not end the session");
        assertFalse(session.isDone());
        assertTrue(client.isConnected());

        fresh.serverClose(1000, "bye");
        session.get(2, TimeUnit.SECONDS);
        await(() -> disconnects.size() == 1, "session end");
    }

    @Test
    void forceReconnect_dropsEventsFromReplacedConnection() throws Exception {
        List<String> raws = new CopyOnWriteArrayList<>();
        client.on(ChatEvent.RAW, (RawEvent e) -> raws.add(e.raw()));
        client.connectToChat();
        FakeChatConnection old = factory.last();
        old.establish();

        client.forceReconnect();
        FakeChatConnection fresh = factory.last();
        old.deliver(ChatEvent.RAW, raw("from old"));
        fresh.deliver(ChatEvent.RAW, raw("from fresh"));

        await(() -> raws.contains("from fresh"), "fresh delivery");
        assertEquals(List.of("from fresh"), raws);
    }

    @Test
    void forceReconnect_withoutSession_startsNewSession() {
        CompletableFuture<Void> session = client.forceReconnect();

        assertEquals(1, factory.created.size());
        assertSame(session, client.connectToChat());
    }

    @Test
    void reconnectFromDisconnectedListener_startsNewSession() throws Exception {
        AtomicInteger restarts = new AtomicInteger();
        client.once(
                ChatEvent.DISCONNECTED,
                (DisconnectedEvent e) -> {
                    restarts.incrementAndGet();
                    client.connectToChat();
                });
        CompletableFuture<Void> first = client.connectToChat();
        factory.last().establish();

        factory.last().serverClose(1000, "restart");

        first.get(2, TimeUnit.SECONDS);
        await(() -> factory.created.size() == 2, "new session");
        assertEquals(1, restarts.get());
        assertNotSame(first, client.connectToChat());
    }

    @Test
    void close_isTerminalEvenForListenersThatReconnect() throws Exception {
        client.on(ChatEvent.DISCONNECTED, (DisconnectedEvent e) -> client.connectToChat());
        CompletableFuture<Void> session = client.connectToChat();
        factory.last().establish();

        client.close();

        session.get(2, TimeUnit.SECONDS);
        await(() -> disconnects.size() == 1, "DISCONNECTED");
        Thread.sleep(50);
        assertEquals(1, factory.created.size(), "A closed client must not come back");
        assertEquals(0, factory.live());
        assertTrue(client.connectToChat().isCompletedExceptionally());
        assertTrue(client.forceReconnect().isCompletedExceptionally());
    }

    @Test
    void reconnect_delegatesToCurrentConnection() throws Exception {
        assertTrue(client.reconnect().isCompletedExceptionally(), "No session yet");

        client.connectToChat();
        factory.last().establish();
        client.reconnect().get(1, TimeUnit.SECONDS);

        assertEquals(1, factory.last().reconnectCalls.get());
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
    void ready_withoutSession_failsWithIllegalState() throws Exception {
        assertInstanceOf(IllegalStateException.class, failureOf(client.ready()), "Before connect");

        CompletableFuture<Void> session = client.connectToChat();
        client.disconnect();
        session.get(2, TimeUnit.SECONDS);

        assertInstanceOf(
                IllegalStateException.class, failureOf(client.ready()), "After the session ended");
    }

    @Test
    void ready_afterClose_failsWithIllegalState() {
        client.connectToChat();

        client.close();

        Throwable cause = failureOf(client.ready());
        assertInstanceOf(IllegalStateException.class, cause);
        assertEquals("Client is closed", cause.getMessage());
    }

    @Test
    void ready_completesOnJoinOutsideTheLane() throws Exception {
        client.connectToChat();
        CompletableFuture<Void> ready = client.ready();
        AtomicReference<Thread> completedOn = new AtomicReference<>();
        CompletableFuture<Void> observed =
                ready.whenComplete((v, e) -> completedOn.set(Thread.currentThread()));
        client.ready().complete(null);

        assertFalse(ready.isDone(), "Not joined yet");
        assertFalse(client.ready().isDone(), "A caller completing its future must not open it");

        factory.last().establish();

        observed.get(2, TimeUnit.SECONDS);
        assertSame(
                Thread.currentThread(),
                completedOn.get(),
                "Completed by the thread that saw the join, not by a lane task");
        assertTrue(joined(client.ready()), "Once joined, ready() is already completed");
    }

    @Test
    void ready_waitsWhileTheConnectionRecovers() throws Exception {
        client.connectToChat();
        FakeChatConnection connection = factory.last();
        connection.establish();
        connection.drop();

        CompletableFuture<Void> ready = client.ready();
        assertFalse(ready.isDone());

        connection.establish();
        ready.get(2, TimeUnit.SECONDS);
    }

    @Test
    void ready_survivesForceReconnectAndCompletesOnTheNewConnection() throws Exception {
        client.connectToChat();
        FakeChatConnection old = factory.last();
        CompletableFuture<Void> before = client.ready();

        client.forceReconnect();
        FakeChatConnection fresh = factory.last();
        CompletableFuture<Void> after = client.ready();

        assertTrue(old.isClosed());
        assertFalse(before.isDone(), "Closing the replaced connection must not fail ready()");
        assertFalse(after.isDone());

        fresh.establish();

        before.get(2, TimeUnit.SECONDS);
        after.get(2, TimeUnit.SECONDS);
    }

    @Test
    void ready_afterForceReconnectOfJoinedSession_waitsForTheNewConnection() throws Exception {
        client.connectToChat();
        factory.last().establish();
        assertTrue(joined(client.ready()));

        client.forceReconnect();
        CompletableFuture<Void> ready = client.ready();

        assertFalse(ready.isDone(), "The new connection has not joined yet");
        factory.last().establish();
        ready.get(2, TimeUnit.SECONDS);
    }

    @Test
    void ready_failsWhenDisconnected() {
        client.connectToChat();
        CompletableFuture<Void> ready = client.ready();

        client.disconnect();

        assertInstanceOf(ConnectionException.class, failureOf(ready));
    }

    @Test
    void ready_failsWhenDisconnectedDuringForceReconnect() {
        client.connectToChat();
        CompletableFuture<Void> ready = client.ready();
        client.forceReconnect();

        client.disconnect();

        assertInstanceOf(ConnectionException.class, failureOf(ready));
    }

    @Test
    void ready_failsWhenTheSessionFails() {
        client.connectToChat();
        CompletableFuture<Void> ready = client.ready();

        factory.last().fail("retries exhausted");

        assertInstanceOf(ConnectionException.class, failureOf(ready));
    }

    /** 리스너로 lane을 붙잡는다. 돌려준 latch를 내리면 풀린다. lane에서 도는 세션 종료 처리(onTerminated)를 늦출 때 쓴다. */
    private CountDownLatch holdLane(FakeChatConnection connection) throws Exception {
        CountDownLatch holding = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        client.once(
                ChatEvent.RAW,
                (RawEvent e) -> {
                    holding.countDown();
                    try {
                        release.await(5, TimeUnit.SECONDS);
                    } catch (InterruptedException ex) {
                        Thread.currentThread().interrupt();
                    }
                });
        connection.deliver(ChatEvent.RAW, raw("hold"));
        assertTrue(holding.await(2, TimeUnit.SECONDS), "lane is held");
        return release;
    }

    @Test
    void ready_takenAfterTheServerEndsTheSession_fails() throws Exception {
        client.connectToChat();
        FakeChatConnection connection = factory.last();
        connection.establish();
        CompletableFuture<Void> whileJoined = client.ready();
        CountDownLatch release = holdLane(connection);

        connection.serverClose(4000, "kicked");
        Throwable cause = failureOf(client.ready());
        release.countDown();

        assertTrue(joined(whileJoined), "A ready() taken while joined stays completed");
        assertInstanceOf(
                ConnectionException.class, cause, "Fails before the lane ends the session");
        assertTrue(cause.getMessage().contains("4000"));
        await(() -> disconnects.size() == 1, "DISCONNECTED");
        assertEquals(4000, disconnects.getFirst().statusCode());
    }

    @Test
    void ready_failureAndForceReconnectAgreeOnTheSessionsEnd() throws Exception {
        // ready()가 실패한 뒤 lane이 세션 종료를 처리하기 전에 forceReconnect가 와도, 이미 실패를 알린 세션을 되살리면 안 된다.
        CompletableFuture<Void> session = client.connectToChat();
        FakeChatConnection connection = factory.last();
        CompletableFuture<Void> ready = client.ready();
        CountDownLatch release = holdLane(connection);

        connection.fail("retries exhausted");
        assertInstanceOf(ConnectionException.class, failureOf(ready));
        CompletableFuture<Void> forced = client.forceReconnect();
        release.countDown();

        assertNotSame(session, forced, "forceReconnect starts a new session");
        assertInstanceOf(ConnectionException.class, failureOf(session));
        await(() -> disconnects.size() == 1, "the failed session ends as ready() reported");
        CompletableFuture<Void> next = client.ready();
        factory.last().establish();
        next.get(2, TimeUnit.SECONDS);
        Thread.sleep(50);
        assertEquals(1, disconnects.size());
    }

    @Test
    void ready_canBeAwaitedInsideAListener() throws Exception {
        // 리스너가 lane을 붙잡고 기다리는 동안에도 준비 완료는 lane 없이 전달돼야 한다.
        AtomicReference<Object> outcome = new AtomicReference<>();
        CountDownLatch waiting = new CountDownLatch(1);
        client.once(
                ChatEvent.RECONNECTING,
                (ReconnectingEvent e) -> {
                    CompletableFuture<Void> ready = client.ready();
                    waiting.countDown();
                    try {
                        ready.get(5, TimeUnit.SECONDS);
                        outcome.set("joined");
                    } catch (Exception ex) {
                        outcome.set(ex);
                    }
                });
        client.connectToChat();
        factory.last().establish();

        client.forceReconnect();
        assertTrue(waiting.await(2, TimeUnit.SECONDS), "listener is waiting on the lane");
        factory.last().establish();

        await(() -> outcome.get() != null, "listener finished");
        assertEquals("joined", outcome.get());
        await(
                () -> lifecycle.stream().anyMatch(e -> e.eventType() == ChatEvent.RECONNECTED),
                "the lane keeps delivering");
    }

    @Test
    void ready_failureReachesAListenerWaitingOnTheLane() throws Exception {
        // 세션 종료 처리(onTerminated)는 lane에서 돌기 때문에, ready()의 실패가 그것을 기다리면 교착 상태가 된다.
        AtomicReference<Throwable> outcome = new AtomicReference<>();
        CountDownLatch waiting = new CountDownLatch(1);
        client.once(
                ChatEvent.RAW,
                (RawEvent e) -> {
                    CompletableFuture<Void> ready = client.ready();
                    waiting.countDown();
                    try {
                        ready.get(5, TimeUnit.SECONDS);
                    } catch (ExecutionException ex) {
                        outcome.set(ex.getCause());
                    } catch (Exception ex) {
                        outcome.set(ex);
                    }
                });
        client.connectToChat();
        FakeChatConnection connection = factory.last();
        connection.establish();
        connection.drop();
        connection.deliver(ChatEvent.RAW, raw("hello"));
        assertTrue(waiting.await(2, TimeUnit.SECONDS), "listener is waiting on the lane");

        connection.fail("retries exhausted");

        await(() -> outcome.get() != null, "listener finished");
        assertInstanceOf(ConnectionException.class, outcome.get());
        await(() -> disconnects.size() == 1, "the session ends once the listener returns");
    }

    @Test
    void ready_repeatedCallsDoNotPileUpOnTheConnection() {
        client.connectToChat();
        FakeChatConnection connection = factory.last();
        int terminatedBaseline = connection.terminatedDependents();

        for (int i = 0; i < 100; i++) {
            client.ready();
        }
        connection.establish();
        for (int i = 0; i < 100; i++) {
            client.ready();
        }

        assertEquals(
                terminatedBaseline,
                connection.terminatedDependents(),
                "ready() must not hang work on terminated(), while joining or joined");
    }

    @Test
    void synchronousConnections_areReadyImmediately() {
        SOOPChatClient c = newClient(FakeConnectionFactory.synchronous());

        c.connectToChat();
        assertTrue(joined(c.ready()));
        c.forceReconnect();
        assertTrue(joined(c.ready()));
        c.close();
    }

    @Test
    void synchronousConnections_workWithoutDeadlock() throws Exception {
        FakeConnectionFactory sync = FakeConnectionFactory.synchronous();
        SOOPChatClient c = newClient(sync);

        CompletableFuture<Void> session = c.connectToChat();
        assertTrue(c.isConnected());
        c.forceReconnect();
        c.disconnect();

        session.get(1, TimeUnit.SECONDS);
        assertEquals(1, disconnects.size());
        assertEquals(0, sync.live());
    }
}
