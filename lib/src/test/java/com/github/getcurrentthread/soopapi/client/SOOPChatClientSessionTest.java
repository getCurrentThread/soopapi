package com.github.getcurrentthread.soopapi.client;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
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
