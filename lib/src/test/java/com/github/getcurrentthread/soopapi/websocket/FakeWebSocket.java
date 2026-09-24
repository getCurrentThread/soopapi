package com.github.getcurrentthread.soopapi.websocket;

import java.io.IOException;
import java.net.URI;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 테스트용 WebSocket. JDK처럼 텍스트 송신이 끝나기 전에 다음 송신이 오면 {@code IllegalStateException("Send pending")}으로
 * 실패하고, 서버 쪽 동작(수신, 닫힘, 오류)을 흉내 낼 수 있습니다.
 */
final class FakeWebSocket implements WebSocket {
    final Listener listener;
    final List<String> sent = new CopyOnWriteArrayList<>();
    final AtomicInteger sendPendingViolations = new AtomicInteger();
    final AtomicInteger closeCalls = new AtomicInteger();
    final AtomicLong requested = new AtomicLong();
    volatile boolean aborted;
    volatile boolean manualSends;
    volatile Throwable failNextSend;
    private CompletableFuture<WebSocket> inFlight;

    FakeWebSocket(Listener listener) {
        this.listener = listener;
    }

    @Override
    public CompletableFuture<WebSocket> sendText(CharSequence data, boolean last) {
        CompletableFuture<WebSocket> f;
        synchronized (this) {
            if (aborted) {
                return CompletableFuture.failedFuture(new IOException("aborted"));
            }
            if (inFlight != null && !inFlight.isDone()) {
                sendPendingViolations.incrementAndGet();
                return CompletableFuture.failedFuture(new IllegalStateException("Send pending"));
            }
            sent.add(data.toString());
            Throwable failure = failNextSend;
            if (failure != null) {
                failNextSend = null;
                inFlight = CompletableFuture.failedFuture(failure);
                return inFlight;
            }
            f = inFlight = new CompletableFuture<>();
        }
        if (!manualSends) {
            f.complete(this);
        }
        return f;
    }

    /** 수동 모드에서 진행 중인 텍스트 송신 하나를 완료합니다. 완료할 송신이 있었으면 true. */
    boolean completeSend() {
        CompletableFuture<WebSocket> f;
        synchronized (this) {
            f = inFlight;
        }
        return f != null && f.complete(this);
    }

    @Override
    public CompletableFuture<WebSocket> sendBinary(ByteBuffer data, boolean last) {
        return CompletableFuture.completedFuture(this);
    }

    @Override
    public CompletableFuture<WebSocket> sendPing(ByteBuffer message) {
        return CompletableFuture.completedFuture(this);
    }

    @Override
    public CompletableFuture<WebSocket> sendPong(ByteBuffer message) {
        return CompletableFuture.completedFuture(this);
    }

    @Override
    public CompletableFuture<WebSocket> sendClose(int statusCode, String reason) {
        closeCalls.incrementAndGet();
        return CompletableFuture.completedFuture(this);
    }

    @Override
    public void request(long n) {
        requested.addAndGet(n);
    }

    @Override
    public String getSubprotocol() {
        return "chat";
    }

    @Override
    public boolean isOutputClosed() {
        return aborted;
    }

    @Override
    public boolean isInputClosed() {
        return aborted;
    }

    @Override
    public void abort() {
        CompletableFuture<WebSocket> f;
        synchronized (this) {
            aborted = true;
            f = inFlight;
        }
        if (f != null) {
            f.completeExceptionally(new IOException("aborted"));
        }
    }

    void serverText(String text) {
        listener.onText(this, text, true);
    }

    void serverClose(int code, String reason) {
        listener.onClose(this, code, reason);
    }

    void serverError(Throwable error) {
        listener.onError(this, error);
    }

    /** 시도마다 동작을 지정할 수 있는 가짜 opener. */
    static final class Opener implements WebSocketManager.Opener {
        enum Mode {
            /** 즉시 열린다. */
            SUCCEED,
            /** 여는 데 실패한다. */
            FAIL,
            /** 열린 뒤 곧바로 1006으로 끊긴다. */
            OPEN_THEN_DROP,
            /** future를 테스트가 직접 완료한다. */
            MANUAL,
            /** future가 완료되기 전에 onOpen과 첫 프레임이 먼저 온다. */
            OPEN_EARLY,
            /** opener 자체가 예외를 던진다. */
            THROW
        }

        final List<FakeWebSocket> sockets = new CopyOnWriteArrayList<>();
        final List<CompletableFuture<WebSocket>> pending = new CopyOnWriteArrayList<>();
        final AtomicInteger attempts = new AtomicInteger();
        private final Deque<Mode> script = new ArrayDeque<>();
        volatile Mode defaultMode = Mode.SUCCEED;
        volatile boolean manualSends;

        synchronized Opener then(Mode... modes) {
            script.addAll(List.of(modes));
            return this;
        }

        private synchronized Mode nextMode() {
            Mode m = script.poll();
            return m != null ? m : defaultMode;
        }

        FakeWebSocket last() {
            return sockets.getLast();
        }

        @Override
        public CompletableFuture<WebSocket> open(URI uri, Listener listener) {
            attempts.incrementAndGet();
            Mode mode = nextMode();
            if (mode == Mode.THROW) {
                throw new IllegalStateException("opener failure");
            }
            if (mode == Mode.FAIL) {
                return CompletableFuture.failedFuture(new IOException("connection refused"));
            }
            FakeWebSocket ws = new FakeWebSocket(listener);
            ws.manualSends = manualSends;
            sockets.add(ws);
            switch (mode) {
                case SUCCEED -> {
                    listener.onOpen(ws);
                    return CompletableFuture.completedFuture(ws);
                }
                case OPEN_THEN_DROP -> {
                    listener.onOpen(ws);
                    Executor later = CompletableFuture.delayedExecutor(20, TimeUnit.MILLISECONDS);
                    later.execute(() -> ws.serverClose(1006, ""));
                    return CompletableFuture.completedFuture(ws);
                }
                case OPEN_EARLY -> {
                    listener.onOpen(ws);
                    ws.serverText("early");
                    CompletableFuture<WebSocket> f = new CompletableFuture<>();
                    pending.add(f);
                    return f;
                }
                default -> {
                    CompletableFuture<WebSocket> f = new CompletableFuture<>();
                    pending.add(f);
                    return f;
                }
            }
        }
    }
}
