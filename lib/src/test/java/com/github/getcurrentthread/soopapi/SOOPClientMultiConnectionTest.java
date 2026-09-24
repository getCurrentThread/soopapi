package com.github.getcurrentthread.soopapi;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.github.getcurrentthread.soopapi.client.SOOPChatClient;
import com.github.getcurrentthread.soopapi.config.SOOPClientConfig;
import com.github.getcurrentthread.soopapi.connection.FakeChatConnection;
import com.github.getcurrentthread.soopapi.connection.FakeConnectionFactory;
import com.github.getcurrentthread.soopapi.event.ChatEvent;
import com.github.getcurrentthread.soopapi.event.StreamEventListener;
import com.github.getcurrentthread.soopapi.event.model.ChatMessageEvent;
import com.github.getcurrentthread.soopapi.event.model.DisconnectedEvent;
import com.github.getcurrentthread.soopapi.event.model.ReconnectingEvent;

public class SOOPClientMultiConnectionTest {

    private FakeConnectionFactory factory;
    private SOOPClient client;

    @BeforeEach
    public void setup() {
        // 연결은 수립시키지 않는다. 세션이 계속 진행 중이라 재연결 테스트가 결정적이다.
        factory = FakeConnectionFactory.async();
        client = new SOOPClient(new SOOPClientConfig.Builder().build(), factory);
    }

    @AfterEach
    public void teardown() {
        client.close();
    }

    private static ChatMessageEvent sampleChat(String msg) {
        return new ChatMessageEvent(
                msg,
                "user",
                0,
                0,
                "nick",
                "0",
                "0",
                "",
                "",
                ChatEvent.CHAT_MESSAGE,
                "raw",
                System.currentTimeMillis());
    }

    @Test
    public void add_duplicateBid_returnsSameInstance() {
        SOOPChatClient a = client.add("streamer1");
        SOOPChatClient b = client.add("streamer1");
        assertSame(a, b, "add() with same bid should return same instance");
        assertEquals(1, client.streamerIds().size());
    }

    @Test
    public void add_thenRemove_returnsTrueAndDetaches() {
        client.add("streamer1");
        assertNotNull(client.get("streamer1"));
        assertTrue(client.remove("streamer1"));
        assertNull(client.get("streamer1"));
        assertFalse(client.streamerIds().contains("streamer1"));
    }

    @Test
    public void remove_unknown_returnsFalse() {
        assertFalse(client.remove("nope"));
    }

    @Test
    public void on_beforeAdd_propagatesToFutureStream() throws Exception {
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<String> receivedBid = new AtomicReference<>();
        AtomicReference<ChatMessageEvent> receivedEvent = new AtomicReference<>();

        client.on(
                ChatEvent.CHAT_MESSAGE,
                (String bid, ChatMessageEvent e) -> {
                    receivedBid.set(bid);
                    receivedEvent.set(e);
                    latch.countDown();
                });

        SOOPChatClient a = client.add("streamerA");
        a.getEventEmitter().emit(ChatEvent.CHAT_MESSAGE, sampleChat("hi"));

        assertTrue(latch.await(1, TimeUnit.SECONDS));
        assertEquals("streamerA", receivedBid.get());
        assertEquals("hi", receivedEvent.get().message());
    }

    @Test
    public void on_afterAdd_propagatesToExistingStreams() throws Exception {
        SOOPChatClient a = client.add("streamerA");
        SOOPChatClient b = client.add("streamerB");

        CountDownLatch latch = new CountDownLatch(2);
        Set<String> seenBids = java.util.Collections.synchronizedSet(new java.util.HashSet<>());

        client.on(
                ChatEvent.CHAT_MESSAGE,
                (String bid, ChatMessageEvent e) -> {
                    seenBids.add(bid);
                    latch.countDown();
                });

        a.getEventEmitter().emit(ChatEvent.CHAT_MESSAGE, sampleChat("a"));
        b.getEventEmitter().emit(ChatEvent.CHAT_MESSAGE, sampleChat("b"));

        assertTrue(latch.await(1, TimeUnit.SECONDS));
        assertEquals(Set.of("streamerA", "streamerB"), seenBids);
    }

    @Test
    public void off_removesFromAllStreams() throws Exception {
        SOOPChatClient a = client.add("streamerA");
        SOOPChatClient b = client.add("streamerB");

        AtomicInteger count = new AtomicInteger();
        StreamEventListener<ChatMessageEvent> listener = (bid, e) -> count.incrementAndGet();

        client.on(ChatEvent.CHAT_MESSAGE, listener);
        client.off(ChatEvent.CHAT_MESSAGE, listener);

        a.getEventEmitter().emit(ChatEvent.CHAT_MESSAGE, sampleChat("a"));
        b.getEventEmitter().emit(ChatEvent.CHAT_MESSAGE, sampleChat("b"));
        Thread.sleep(100);

        assertEquals(0, count.get(), "Listener should not be called after off()");
    }

    @Test
    public void remove_detachesGlobalSub_othersUnaffected() throws Exception {
        SOOPChatClient a = client.add("streamerA");
        SOOPChatClient b = client.add("streamerB");

        AtomicInteger count = new AtomicInteger();
        AtomicReference<String> lastBid = new AtomicReference<>();

        client.on(
                ChatEvent.CHAT_MESSAGE,
                (String bid, ChatMessageEvent e) -> {
                    lastBid.set(bid);
                    count.incrementAndGet();
                });

        client.remove("streamerA");

        // a is closed, emitter cleared — emit via b only
        b.getEventEmitter().emit(ChatEvent.CHAT_MESSAGE, sampleChat("b"));
        Thread.sleep(100);

        assertEquals(1, count.get());
        assertEquals("streamerB", lastBid.get());
    }

    @Test
    public void streamerIds_returnsSnapshot() {
        client.add("a");
        client.add("b");
        client.add("c");
        assertEquals(Set.of("a", "b", "c"), client.streamerIds());

        client.remove("b");
        assertEquals(Set.of("a", "c"), client.streamerIds());
    }

    @Test
    public void reconnect_unknown_returnsFailedFuture() {
        CompletableFuture<Void> f = client.reconnect("nonexistent");
        assertTrue(f.isCompletedExceptionally());
        ExecutionException ex = assertThrows(ExecutionException.class, f::get);
        assertInstanceOf(IllegalArgumentException.class, ex.getCause());
    }

    @Test
    public void reconnect_existing_emitsReconnectingAndReplacesConnection() throws Exception {
        SOOPChatClient a = client.add("streamerA");
        FakeChatConnection old = factory.last();

        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<ReconnectingEvent> received = new AtomicReference<>();
        a.on(
                ChatEvent.RECONNECTING,
                (ReconnectingEvent e) -> {
                    received.set(e);
                    latch.countDown();
                });

        CompletableFuture<Void> session = client.reconnect("streamerA");

        assertTrue(latch.await(2, TimeUnit.SECONDS), "RECONNECTING should be emitted");
        assertEquals(1, received.get().attemptNumber());
        assertEquals(1, received.get().maxAttempts());
        assertEquals(0L, received.get().delayMs());
        assertTrue(old.isClosed());
        assertEquals(2, factory.created.size());
        assertSame(a.connectToChat(), session, "reconnect keeps the session future");
    }

    @Test
    public void reconnectAll_emitsForEachStream() throws Exception {
        SOOPChatClient a = client.add("streamerA");
        SOOPChatClient b = client.add("streamerB");

        CountDownLatch latch = new CountDownLatch(2);
        AtomicInteger total = new AtomicInteger();
        a.on(
                ChatEvent.RECONNECTING,
                (ReconnectingEvent e) -> {
                    total.incrementAndGet();
                    latch.countDown();
                });
        b.on(
                ChatEvent.RECONNECTING,
                (ReconnectingEvent e) -> {
                    total.incrementAndGet();
                    latch.countDown();
                });

        client.reconnectAll();

        assertTrue(latch.await(2, TimeUnit.SECONDS), "Each stream should emit RECONNECTING");
        assertEquals(2, total.get());
        assertEquals(2, factory.live());
    }

    @Test
    public void add_passesClientRetryLimitToStreams() {
        try (SOOPClient limited =
                new SOOPClient(
                        new SOOPClientConfig.Builder().maxRetryAttempts(7).build(), factory)) {
            limited.add("streamerA");
            assertEquals(7, factory.last().config.getMaxRetryAttempts());
        }
    }

    @Test
    public void add_afterSessionEnded_startsNewSession() throws Exception {
        SOOPChatClient a = client.add("streamerA");
        CompletableFuture<Void> first = a.connectToChat();
        a.disconnect();
        first.get(2, TimeUnit.SECONDS);

        assertSame(a, client.add("streamerA"));

        assertEquals(2, factory.created.size());
        assertNotSame(first, a.connectToChat());
    }

    @Test
    public void remove_closesConnectionEvenIfListenerReconnects() throws Exception {
        SOOPChatClient a = client.add("streamerA");
        CountDownLatch disconnected = new CountDownLatch(1);
        a.on(
                ChatEvent.DISCONNECTED,
                (DisconnectedEvent e) -> {
                    a.connectToChat();
                    disconnected.countDown();
                });

        client.remove("streamerA");

        assertTrue(disconnected.await(2, TimeUnit.SECONDS));
        assertEquals(1, factory.created.size(), "A removed stream must not reconnect");
        assertEquals(0, factory.live());
    }

    @Test
    public void close_leavesNoLiveConnections() throws Exception {
        client.add("a");
        client.add("b");
        client.add("c");
        factory.created.getFirst().establish();
        client.reconnect("b");

        client.close();

        assertEquals(0, factory.live());
        assertTrue(client.streamerIds().isEmpty());
    }
}
