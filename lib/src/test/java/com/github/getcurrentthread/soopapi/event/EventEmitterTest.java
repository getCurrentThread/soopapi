package com.github.getcurrentthread.soopapi.event;

import static org.junit.jupiter.api.Assertions.*;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.github.getcurrentthread.soopapi.event.model.ChatMessageEvent;
import com.github.getcurrentthread.soopapi.event.model.DisconnectedEvent;
import com.github.getcurrentthread.soopapi.event.model.UnknownEvent;
import com.github.getcurrentthread.soopapi.exception.EventEmitterException;

public class EventEmitterTest {

    private EventEmitter emitter;

    @BeforeEach
    public void setup() {
        emitter = new EventEmitter();
    }

    @Test
    public void testOnAndEmit() throws Exception {
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<ChatMessageEvent> received = new AtomicReference<>();

        emitter.on(
                ChatEvent.CHAT_MESSAGE,
                (ChatMessageEvent e) -> {
                    received.set(e);
                    latch.countDown();
                });

        ChatMessageEvent event =
                new ChatMessageEvent(
                        "hello",
                        "user1",
                        0,
                        0,
                        "nick1",
                        "0",
                        "0",
                        "",
                        "",
                        ChatEvent.CHAT_MESSAGE,
                        "raw",
                        System.currentTimeMillis());

        emitter.emit(ChatEvent.CHAT_MESSAGE, event);

        assertTrue(latch.await(1, TimeUnit.SECONDS), "Event should be received");
        assertEquals("hello", received.get().message());
        assertEquals("user1", received.get().senderId());
    }

    @Test
    public void testOnce() throws Exception {
        AtomicInteger callCount = new AtomicInteger(0);
        CountDownLatch latch = new CountDownLatch(1);

        emitter.once(
                ChatEvent.CHAT_MESSAGE,
                (ChatMessageEvent e) -> {
                    callCount.incrementAndGet();
                    latch.countDown();
                });

        ChatMessageEvent event =
                new ChatMessageEvent(
                        "msg",
                        "u",
                        0,
                        0,
                        "n",
                        "0",
                        "0",
                        "",
                        "",
                        ChatEvent.CHAT_MESSAGE,
                        "raw",
                        System.currentTimeMillis());

        emitter.emit(ChatEvent.CHAT_MESSAGE, event);
        latch.await(1, TimeUnit.SECONDS);

        emitter.emit(ChatEvent.CHAT_MESSAGE, event);
        Thread.sleep(100);

        assertEquals(1, callCount.get(), "Listener should only be called once");
    }

    @Test
    public void testOff() throws Exception {
        AtomicInteger callCount = new AtomicInteger(0);

        EventListener<ChatMessageEvent> listener = e -> callCount.incrementAndGet();

        emitter.on(ChatEvent.CHAT_MESSAGE, listener);
        emitter.off(ChatEvent.CHAT_MESSAGE, listener);

        ChatMessageEvent event =
                new ChatMessageEvent(
                        "msg",
                        "u",
                        0,
                        0,
                        "n",
                        "0",
                        "0",
                        "",
                        "",
                        ChatEvent.CHAT_MESSAGE,
                        "raw",
                        System.currentTimeMillis());

        emitter.emit(ChatEvent.CHAT_MESSAGE, event);
        Thread.sleep(100);

        assertEquals(0, callCount.get(), "Listener should not be called after off()");
    }

    @Test
    public void testMultipleListeners() throws Exception {
        CountDownLatch latch = new CountDownLatch(2);

        emitter.on(ChatEvent.CHAT_MESSAGE, (ChatMessageEvent e) -> latch.countDown());
        emitter.on(ChatEvent.CHAT_MESSAGE, (ChatMessageEvent e) -> latch.countDown());

        ChatMessageEvent event =
                new ChatMessageEvent(
                        "msg",
                        "u",
                        0,
                        0,
                        "n",
                        "0",
                        "0",
                        "",
                        "",
                        ChatEvent.CHAT_MESSAGE,
                        "raw",
                        System.currentTimeMillis());

        emitter.emit(ChatEvent.CHAT_MESSAGE, event);

        assertTrue(latch.await(1, TimeUnit.SECONDS), "Both listeners should be called");
    }

    @Test
    public void testClear() throws Exception {
        AtomicInteger callCount = new AtomicInteger(0);

        emitter.on(ChatEvent.CHAT_MESSAGE, (ChatMessageEvent e) -> callCount.incrementAndGet());
        emitter.clear();

        ChatMessageEvent event =
                new ChatMessageEvent(
                        "msg",
                        "u",
                        0,
                        0,
                        "n",
                        "0",
                        "0",
                        "",
                        "",
                        ChatEvent.CHAT_MESSAGE,
                        "raw",
                        System.currentTimeMillis());

        emitter.emit(ChatEvent.CHAT_MESSAGE, event);
        Thread.sleep(100);

        assertEquals(0, callCount.get(), "No listeners after clear()");
    }

    @Test
    public void testClearSpecificEvent() throws Exception {
        AtomicInteger chatCount = new AtomicInteger(0);
        AtomicInteger unknownCount = new AtomicInteger(0);
        CountDownLatch latch = new CountDownLatch(1);

        emitter.on(ChatEvent.CHAT_MESSAGE, (ChatMessageEvent e) -> chatCount.incrementAndGet());
        emitter.on(
                ChatEvent.NONE_TYPE,
                (UnknownEvent e) -> {
                    unknownCount.incrementAndGet();
                    latch.countDown();
                });

        emitter.clear(ChatEvent.CHAT_MESSAGE);

        ChatMessageEvent chatEvent =
                new ChatMessageEvent(
                        "msg",
                        "u",
                        0,
                        0,
                        "n",
                        "0",
                        "0",
                        "",
                        "",
                        ChatEvent.CHAT_MESSAGE,
                        "raw",
                        System.currentTimeMillis());
        UnknownEvent unknownEvent =
                new UnknownEvent(
                        9999, "raw", ChatEvent.NONE_TYPE, "raw", System.currentTimeMillis());

        emitter.emit(ChatEvent.CHAT_MESSAGE, chatEvent);
        emitter.emit(ChatEvent.NONE_TYPE, unknownEvent);

        latch.await(1, TimeUnit.SECONDS);

        assertEquals(0, chatCount.get(), "Chat listener should be cleared");
        assertEquals(1, unknownCount.get(), "Unknown listener should still work");
    }

    @Test
    public void testMethodChaining() {
        EventEmitter result =
                emitter.on(ChatEvent.CHAT_MESSAGE, (ChatMessageEvent e) -> {})
                        .on(ChatEvent.JOIN_CHANNEL, e -> {});

        assertSame(emitter, result, "Method chaining should return same emitter");
    }

    @Test
    public void testErrorHandler() throws Exception {
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<EventEmitterException> captured = new AtomicReference<>();

        emitter.setErrorHandler(
                ex -> {
                    captured.set(ex);
                    latch.countDown();
                });

        emitter.on(
                ChatEvent.CHAT_MESSAGE,
                (ChatMessageEvent e) -> {
                    throw new RuntimeException("test error");
                });

        ChatMessageEvent event =
                new ChatMessageEvent(
                        "msg",
                        "u",
                        0,
                        0,
                        "n",
                        "0",
                        "0",
                        "",
                        "",
                        ChatEvent.CHAT_MESSAGE,
                        "raw",
                        System.currentTimeMillis());

        emitter.emit(ChatEvent.CHAT_MESSAGE, event);

        assertTrue(latch.await(1, TimeUnit.SECONDS), "Error handler should be called");
        assertNotNull(captured.get());
        assertEquals(ChatEvent.CHAT_MESSAGE, captured.get().getChatEvent());
        assertInstanceOf(RuntimeException.class, captured.get().getCause());
    }

    @Test
    public void testDisconnectedEventEmission() throws Exception {
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<DisconnectedEvent> received = new AtomicReference<>();

        emitter.on(
                ChatEvent.DISCONNECTED,
                (DisconnectedEvent e) -> {
                    received.set(e);
                    latch.countDown();
                });

        DisconnectedEvent event =
                new DisconnectedEvent(
                        1000,
                        "normal close",
                        false,
                        ChatEvent.DISCONNECTED,
                        "",
                        System.currentTimeMillis());

        emitter.emit(ChatEvent.DISCONNECTED, event);

        assertTrue(latch.await(1, TimeUnit.SECONDS), "DISCONNECTED event should be received");
        assertEquals(1000, received.get().statusCode());
        assertEquals("normal close", received.get().reason());
        assertFalse(received.get().causedByError());
    }
}
