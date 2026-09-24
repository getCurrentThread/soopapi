package com.github.getcurrentthread.soopapi.decoder;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.github.getcurrentthread.soopapi.constant.SOOPConstants;
import com.github.getcurrentthread.soopapi.decoder.message.IMessageDecoder;
import com.github.getcurrentthread.soopapi.event.ChatEvent;
import com.github.getcurrentthread.soopapi.event.EventEmitter;
import com.github.getcurrentthread.soopapi.event.model.ChatMessageEvent;
import com.github.getcurrentthread.soopapi.event.model.RawEvent;
import com.github.getcurrentthread.soopapi.event.model.UnknownEvent;
import com.github.getcurrentthread.soopapi.util.SerialExecutor;

class MessageDispatcherTest {

    private EventEmitter emitter;
    private MessageDispatcher dispatcher;

    @BeforeEach
    void setup() {
        emitter = new EventEmitter();
    }

    @Test
    void nullMessage_isIgnored() {
        dispatcher = createDispatcher(Map.of());
        // 예외가 발생하지 않아야 함
        dispatcher.dispatchMessage(null);
    }

    @Test
    void emptyMessage_isIgnored() {
        dispatcher = createDispatcher(Map.of());
        // 예외가 발생하지 않아야 함
        dispatcher.dispatchMessage("");
    }

    @Test
    void rawEvent_emittedForEveryMessage() {
        dispatcher = createDispatcher(Map.of());

        AtomicReference<String> received = new AtomicReference<>();
        emitter.on(ChatEvent.RAW, (RawEvent e) -> received.set(e.raw()));

        String message = "header" + SOOPConstants.F + "body";
        dispatcher.dispatchMessage(message);

        assertEquals(message, received.get(), "RAW event should be emitted");
    }

    @Test
    void messageWithoutSeparator_isIgnored() {
        dispatcher = createDispatcher(Map.of());

        AtomicReference<String> receivedRaw = new AtomicReference<>();
        emitter.on(ChatEvent.RAW, (RawEvent e) -> receivedRaw.set(e.raw()));

        // F 구분자가 없는 메시지 - RAW 이벤트만 발생해야 함
        dispatcher.dispatchMessage("no_separator_message");

        assertEquals(
                "no_separator_message", receivedRaw.get(), "RAW event should still be emitted");
    }

    @Test
    void knownEvent_routedToDecoder() {
        IMessageDecoder chatDecoder =
                (parts, raw) ->
                        new ChatMessageEvent(
                                parts[0],
                                parts.length > 1 ? parts[1] : "",
                                0,
                                0,
                                "nick",
                                "0",
                                "0",
                                "",
                                "",
                                ChatEvent.CHAT_MESSAGE,
                                raw,
                                System.currentTimeMillis());

        dispatcher = createDispatcher(Map.of(ChatEvent.CHAT_MESSAGE, chatDecoder));

        AtomicReference<ChatMessageEvent> received = new AtomicReference<>();
        emitter.on(ChatEvent.CHAT_MESSAGE, (ChatMessageEvent e) -> received.set(e));

        // 서비스 코드 5 (CHAT_MESSAGE)로 메시지 구성
        // 헤더 형식: ESC + TAB + "0005" + 길이 + 접미사
        String header = SOOPConstants.ESC + "0005" + "000010" + "00";
        String payload = "Hello" + SOOPConstants.F + "user1";
        String message = header + SOOPConstants.F + payload;

        dispatcher.dispatchMessage(message);

        assertNotNull(received.get(), "Chat message event should be dispatched");
        assertEquals("Hello", received.get().message());
    }

    @Test
    void unknownEvent_dispatchedAsUnknown() {
        dispatcher = createDispatcher(Map.of());

        AtomicReference<UnknownEvent> received = new AtomicReference<>();
        emitter.on(ChatEvent.NONE_TYPE, (UnknownEvent e) -> received.set(e));

        // 알 수 없는 서비스 코드 (9999) 사용
        String header = SOOPConstants.ESC + "9999" + "000010" + "00";
        String message = header + SOOPConstants.F + "some_data";

        dispatcher.dispatchMessage(message);

        UnknownEvent event = received.get();
        assertNotNull(event, "Unknown event should be dispatched");
        assertEquals(9999, event.code(), "Original service code from the header");
        assertEquals(ChatEvent.NONE_TYPE, event.eventType());
        assertEquals(message, event.originalMessage());
        assertEquals(message, event.raw());
    }

    @Test
    void knownCodeWithoutDecoder_isDeliveredOnNoneTypeOnly() {
        dispatcher = createDispatcher(Map.of());

        AtomicInteger chatDelivered = new AtomicInteger();
        emitter.on(ChatEvent.CHAT_MESSAGE, e -> chatDelivered.incrementAndGet());
        AtomicReference<UnknownEvent> received = new AtomicReference<>();
        emitter.on(ChatEvent.NONE_TYPE, (UnknownEvent e) -> received.set(e));

        // CHAT_MESSAGE(5)는 알려진 코드지만 디코더 맵에 없다
        String message = SOOPConstants.ESC + "0005" + "000010" + "00" + SOOPConstants.F + "data";
        dispatcher.dispatchMessage(message);

        assertEquals(0, chatDelivered.get(), "UnknownEvent must not reach CHAT_MESSAGE listeners");
        UnknownEvent event = received.get();
        assertNotNull(event, "Packet without a decoder should arrive on NONE_TYPE");
        assertEquals(5, event.code());
        assertEquals(ChatEvent.NONE_TYPE, event.eventType());
        assertEquals(message, event.raw());
    }

    @Test
    void decoderException_doesNotCrashDispatcher() {
        IMessageDecoder failingDecoder =
                (parts, raw) -> {
                    throw new RuntimeException("Decoder error");
                };

        dispatcher = createDispatcher(Map.of(ChatEvent.CHAT_MESSAGE, failingDecoder));

        AtomicReference<String> receivedRaw = new AtomicReference<>();
        emitter.on(ChatEvent.RAW, (RawEvent e) -> receivedRaw.set(e.raw()));
        emitter.on(ChatEvent.CHAT_MESSAGE, (ChatMessageEvent e) -> {});

        String header = SOOPConstants.ESC + "0005" + "000010" + "00";
        String message = header + SOOPConstants.F + "data";

        // 예외가 발생하지 않아야 함; 오류는 내부적으로 처리됨
        dispatcher.dispatchMessage(message);

        assertEquals(message, receivedRaw.get(), "RAW event should still be emitted");
    }

    @Test
    void messageWithNoListeners_isSkipped() {
        dispatcher = createDispatcher(Map.of());

        // 특정 이벤트에 대해 등록된 리스너가 없음
        // 예외가 발생하지 않아야 함
        String header = SOOPConstants.ESC + "0005" + "000010" + "00";
        String message = header + SOOPConstants.F + "data";

        dispatcher.dispatchMessage(message);
        // 여기까지 예외 없이 도달하면 테스트 통과
    }

    @Test
    void malformedHeader_isDroppedAndCannotReachSystemEvents() {
        dispatcher = createDispatcher(Map.of());

        AtomicInteger delivered = new AtomicInteger();
        emitter.on(ChatEvent.DISCONNECTED, e -> delivered.incrementAndGet());
        emitter.on(ChatEvent.NONE_TYPE, e -> delivered.incrementAndGet());

        // "-003"은 예전에는 DISCONNECTED(-3)로 해석될 수 있었다.
        dispatcher.dispatchMessage(SOOPConstants.ESC + "-003" + "000010" + SOOPConstants.F + "x");
        dispatcher.dispatchMessage("noTab" + SOOPConstants.F + "x");

        assertEquals(0, delivered.get());
    }

    @Test
    void decoderReturningNull_emitsNothing() {
        dispatcher = createDispatcher(Map.of(ChatEvent.CHAT_MESSAGE, (parts, raw) -> null));

        AtomicInteger delivered = new AtomicInteger();
        emitter.on(ChatEvent.CHAT_MESSAGE, e -> delivered.incrementAndGet());

        dispatcher.dispatchMessage(SOOPConstants.ESC + "0005000010" + SOOPConstants.F + "x");

        assertEquals(0, delivered.get());
    }

    @Test
    void deactivate_dropsQueuedAndLaterPackets() {
        List<Runnable> queued = new ArrayList<>();
        dispatcher = new MessageDispatcher(Map.of(), queued::add, emitter);

        AtomicInteger delivered = new AtomicInteger();
        emitter.on(ChatEvent.RAW, e -> delivered.incrementAndGet());

        dispatcher.dispatchMessage("queued");
        dispatcher.deactivate();
        dispatcher.dispatchMessage("late");
        queued.forEach(Runnable::run);

        assertEquals(1, queued.size(), "Packets after deactivate() are not even queued");
        assertEquals(0, delivered.get(), "Queued packets are dropped after deactivate()");
    }

    @Test
    void laneDelivery_preservesOrderOnMultiThreadPool() throws Exception {
        int n = 1000;
        List<String> received = new CopyOnWriteArrayList<>();
        AtomicInteger inFlight = new AtomicInteger();
        AtomicInteger overlaps = new AtomicInteger();
        CountDownLatch done = new CountDownLatch(n);

        emitter.on(
                ChatEvent.RAW,
                (RawEvent e) -> {
                    if (inFlight.incrementAndGet() > 1) {
                        overlaps.incrementAndGet();
                    }
                    received.add(e.raw());
                    inFlight.decrementAndGet();
                    done.countDown();
                });

        try (ExecutorService pool = Executors.newFixedThreadPool(8)) {
            dispatcher = new MessageDispatcher(Map.of(), new SerialExecutor(pool), emitter);
            for (int i = 0; i < n; i++) {
                dispatcher.dispatchMessage("m" + i);
            }
            assertTrue(done.await(10, TimeUnit.SECONDS));
        }

        assertEquals(0, overlaps.get(), "Listeners of one stream must not run concurrently");
        for (int i = 0; i < n; i++) {
            assertEquals("m" + i, received.get(i));
        }
    }

    private MessageDispatcher createDispatcher(Map<ChatEvent, IMessageDecoder> decoders) {
        // 동기 실행을 위한 익명 ExecutorService
        ExecutorService directExecutor =
                new java.util.concurrent.AbstractExecutorService() {
                    @Override
                    public void shutdown() {}

                    @Override
                    public java.util.List<Runnable> shutdownNow() {
                        return java.util.List.of();
                    }

                    @Override
                    public boolean isShutdown() {
                        return false;
                    }

                    @Override
                    public boolean isTerminated() {
                        return false;
                    }

                    @Override
                    public boolean awaitTermination(long timeout, TimeUnit unit) {
                        return true;
                    }

                    @Override
                    public void execute(Runnable command) {
                        command.run();
                    }
                };
        return new MessageDispatcher(decoders, directExecutor, emitter);
    }
}
