package com.github.getcurrentthread.soopapi.websocket;

import static org.junit.jupiter.api.Assertions.*;

import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.github.getcurrentthread.soopapi.decoder.MessageDispatcher;
import com.github.getcurrentthread.soopapi.event.ChatEvent;
import com.github.getcurrentthread.soopapi.event.EventEmitter;
import com.github.getcurrentthread.soopapi.event.model.RawEvent;
import com.github.getcurrentthread.soopapi.util.SerialExecutor;

public class WebSocketListenerBufferTest {

    private static final WebSocketListener.Callbacks NO_CALLBACKS =
            new WebSocketListener.Callbacks() {
                @Override
                public void onInbound() {}

                @Override
                public void onClosed(int statusCode, String reason) {}

                @Override
                public void onFailed(Throwable error) {}
            };

    private EventEmitter emitter;
    private WebSocketListener listener;
    private WebSocket stubWebSocket;

    @BeforeEach
    void setup() {
        emitter = new EventEmitter();
        var dispatcher = new MessageDispatcher(Map.of(), Runnable::run, emitter);
        listener = new WebSocketListener(dispatcher, null, NO_CALLBACKS);
        stubWebSocket = new StubWebSocket();
    }

    @Test
    void smallMessage_dispatchedCorrectly() throws Exception {
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<String> received = new AtomicReference<>();

        emitter.on(
                ChatEvent.RAW,
                (RawEvent e) -> {
                    received.set(e.raw());
                    latch.countDown();
                });

        String msg = "hello";
        listener.onText(stubWebSocket, msg, true);

        assertTrue(latch.await(2, TimeUnit.SECONDS));
        assertEquals(msg, received.get());
    }

    @Test
    void largeMessage_processedAndBufferShrinks() throws Exception {
        CountDownLatch latch = new CountDownLatch(1);

        emitter.on(ChatEvent.RAW, (RawEvent e) -> latch.countDown());

        // DEFAULT_BUFFER_SIZE * 4 (65536자)보다 큰 메시지 구성
        String largeMsg = "x".repeat(WebSocketListener.DEFAULT_BUFFER_SIZE * 5);

        listener.onText(stubWebSocket, largeMsg, true);

        assertTrue(latch.await(2, TimeUnit.SECONDS), "Large message should be dispatched");

        // 대용량 메시지 이후 작은 메시지를 전송하여 정상 동작 확인
        // (버퍼가 재할당됨)
        CountDownLatch latch2 = new CountDownLatch(1);
        AtomicReference<String> received2 = new AtomicReference<>();

        emitter.on(
                ChatEvent.RAW,
                (RawEvent e) -> {
                    received2.set(e.raw());
                    latch2.countDown();
                });

        listener.onText(stubWebSocket, "small", true);

        assertTrue(latch2.await(2, TimeUnit.SECONDS));
        assertEquals("small", received2.get());
    }

    @Test
    void multiPartMessage_assembledCorrectly() throws Exception {
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<String> received = new AtomicReference<>();

        emitter.on(
                ChatEvent.RAW,
                (RawEvent e) -> {
                    received.set(e.raw());
                    latch.countDown();
                });

        listener.onText(stubWebSocket, "hello ", false);
        listener.onText(stubWebSocket, "world", true);

        assertTrue(latch.await(2, TimeUnit.SECONDS));
        assertEquals("hello world", received.get());
    }

    @Test
    void binarySlice_withNonZeroArrayOffset_decodesOnlyTheSlice() {
        AtomicReference<String> received = new AtomicReference<>();
        emitter.on(ChatEvent.RAW, (RawEvent e) -> received.set(e.raw()));

        byte[] backing = "XXXXhello한YYYY".getBytes(StandardCharsets.UTF_8);
        int start = 4;
        int length = "hello한".getBytes(StandardCharsets.UTF_8).length;
        ByteBuffer slice = ByteBuffer.wrap(backing, start, length).slice();
        assertNotEquals(0, slice.arrayOffset());

        listener.onBinary(stubWebSocket, slice, true);

        assertEquals("hello한", received.get());
    }

    @Test
    void binaryReadOnlyBuffer_isDecoded() {
        AtomicReference<String> received = new AtomicReference<>();
        emitter.on(ChatEvent.RAW, (RawEvent e) -> received.set(e.raw()));

        ByteBuffer readOnly =
                ByteBuffer.wrap("read-only".getBytes(StandardCharsets.UTF_8)).asReadOnlyBuffer();

        listener.onBinary(stubWebSocket, readOnly, true);

        assertEquals("read-only", received.get());
    }

    @Test
    void binaryMultibyteCharSplitAcrossFragments_isReassembled() {
        AtomicReference<String> received = new AtomicReference<>();
        emitter.on(ChatEvent.RAW, (RawEvent e) -> received.set(e.raw()));

        byte[] bytes = "a한b".getBytes(StandardCharsets.UTF_8); // 'a' + 3바이트 한글 + 'b'
        ByteBuffer first = ByteBuffer.wrap(bytes, 0, 2).slice(); // 한글 첫 바이트에서 자른다
        ByteBuffer second = ByteBuffer.wrap(bytes, 2, bytes.length - 2).slice();

        listener.onBinary(stubWebSocket, first, false);
        assertNull(received.get());
        listener.onBinary(stubWebSocket, second, true);

        assertEquals("a한b", received.get());
    }

    @Test
    void listenerFailure_stillRequestsNextFrame() {
        CountingWebSocket ws = new CountingWebSocket();
        emitter.on(
                ChatEvent.RAW,
                (RawEvent e) -> {
                    throw new IllegalStateException("listener failure");
                });

        listener.onText(ws, "one", true);
        listener.onBinary(ws, ByteBuffer.wrap(new byte[] {'x'}), true);

        assertEquals(2, ws.requested);
    }

    @Test
    void detachedListener_dropsDataButKeepsReading() {
        AtomicReference<String> received = new AtomicReference<>();
        emitter.on(ChatEvent.RAW, (RawEvent e) -> received.set(e.raw()));
        CountingWebSocket ws = new CountingWebSocket();

        listener.detach();
        listener.onText(ws, "after detach", true);

        assertNull(received.get());
        assertEquals(1, ws.requested, "A detached listener still requests frames");
    }

    @Test
    void backloggedLane_pausesReadsUntilItDrains() {
        List<Runnable> pool = new ArrayList<>();
        SerialExecutor lane = new SerialExecutor(pool::add);
        var dispatcher = new MessageDispatcher(Map.of(), lane, emitter);
        var paced = new WebSocketListener(dispatcher, lane, NO_CALLBACKS);
        CountingWebSocket ws = new CountingWebSocket();

        for (int i = 0; i <= WebSocketListener.HIGH_WATER; i++) {
            paced.onText(ws, "m" + i, true);
        }
        long requestedWhileBacklogged = ws.requested;
        assertEquals(
                WebSocketListener.HIGH_WATER,
                requestedWhileBacklogged,
                "The frame that crosses the high-water mark must not request the next one");

        while (!pool.isEmpty()) {
            pool.removeFirst().run(); // drain은 배치마다 스스로를 다시 제출한다
        }

        assertEquals(requestedWhileBacklogged + 1, ws.requested, "Reads resume after draining");
    }

    /** request() 호출 추적 외에는 아무 동작도 하지 않는 최소한의 WebSocket 스텁. */
    private static class CountingWebSocket extends StubWebSocket {
        long requested;

        @Override
        public void request(long n) {
            requested += n;
        }
    }

    private static class StubWebSocket implements WebSocket {
        @Override
        public java.util.concurrent.CompletableFuture<WebSocket> sendText(
                CharSequence data, boolean last) {
            return java.util.concurrent.CompletableFuture.completedFuture(this);
        }

        @Override
        public java.util.concurrent.CompletableFuture<WebSocket> sendBinary(
                java.nio.ByteBuffer data, boolean last) {
            return java.util.concurrent.CompletableFuture.completedFuture(this);
        }

        @Override
        public java.util.concurrent.CompletableFuture<WebSocket> sendPing(
                java.nio.ByteBuffer message) {
            return java.util.concurrent.CompletableFuture.completedFuture(this);
        }

        @Override
        public java.util.concurrent.CompletableFuture<WebSocket> sendPong(
                java.nio.ByteBuffer message) {
            return java.util.concurrent.CompletableFuture.completedFuture(this);
        }

        @Override
        public java.util.concurrent.CompletableFuture<WebSocket> sendClose(
                int statusCode, String reason) {
            return java.util.concurrent.CompletableFuture.completedFuture(this);
        }

        @Override
        public void request(long n) {}

        @Override
        public String getSubprotocol() {
            return "";
        }

        @Override
        public boolean isOutputClosed() {
            return false;
        }

        @Override
        public boolean isInputClosed() {
            return false;
        }

        @Override
        public void abort() {}
    }
}
