package com.github.getcurrentthread.soopapi.connection;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

import com.github.getcurrentthread.soopapi.config.SOOPChatConfig;
import com.github.getcurrentthread.soopapi.event.ChatEvent;
import com.github.getcurrentthread.soopapi.event.EventEmitter;
import com.github.getcurrentthread.soopapi.event.model.BaseEvent;
import com.github.getcurrentthread.soopapi.event.model.DisconnectedEvent;
import com.github.getcurrentthread.soopapi.exception.ConnectionException;
import com.github.getcurrentthread.soopapi.model.ConnectionStatus;
import com.github.getcurrentthread.soopapi.util.SerialExecutor;

/**
 * 네트워크 없이 동작하는 {@link ChatConnection}. 테스트가 연결 성공·실패·서버 종료를 직접 일으킵니다. {@link ChatConnection} 계약대로
 * {@link #terminated()}는 connect 결과가 정해진 뒤 정확히 한 번 완료됩니다.
 */
public final class FakeChatConnection implements ChatConnection {
    public final SOOPChatConfig config;
    private final EventEmitter emitter;
    private final SerialExecutor lane;
    private final CompletableFuture<Void> connected = new CompletableFuture<>();
    private final CompletableFuture<DisconnectedEvent> terminated = new CompletableFuture<>();
    public final AtomicInteger connectCalls = new AtomicInteger();
    public final AtomicInteger reconnectCalls = new AtomicInteger();
    public final AtomicInteger closeCalls = new AtomicInteger();
    public final AtomicInteger sent = new AtomicInteger();
    private volatile boolean closed;

    FakeChatConnection(SOOPChatConfig config, EventEmitter emitter, SerialExecutor lane) {
        this.config = config;
        this.emitter = emitter;
        this.lane = lane;
    }

    /** 연결 수립을 흉내 냅니다. */
    public void establish() {
        connected.complete(null);
    }

    /** 연결 실패(재시도 소진 포함)를 흉내 냅니다. */
    public void fail(String message) {
        ConnectionException error = new ConnectionException(message);
        connected.completeExceptionally(error);
        terminated.completeExceptionally(error);
    }

    /** 수립된 연결을 서버가 닫는 상황을 흉내 냅니다. */
    public void serverClose(int code, String reason) {
        terminated.complete(
                new DisconnectedEvent(
                        code,
                        reason,
                        false,
                        ChatEvent.DISCONNECTED,
                        "",
                        System.currentTimeMillis()));
    }

    /** 서버에서 이벤트가 들어온 것처럼 lane에서 emit합니다. */
    public void deliver(ChatEvent type, BaseEvent event) {
        lane.execute(
                () -> {
                    if (!closed) {
                        emitter.emit(type, event);
                    }
                });
    }

    public boolean isClosed() {
        return closed;
    }

    @Override
    public CompletableFuture<Void> connect() {
        connectCalls.incrementAndGet();
        return connected.copy();
    }

    @Override
    public CompletableFuture<Void> reconnect() {
        reconnectCalls.incrementAndGet();
        return closed
                ? CompletableFuture.failedFuture(new ConnectionException("closed"))
                : CompletableFuture.completedFuture(null);
    }

    @Override
    public CompletableFuture<DisconnectedEvent> terminated() {
        return terminated.copy();
    }

    @Override
    public CompletableFuture<Void> sendChat(String message) {
        sent.incrementAndGet();
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public CompletableFuture<Void> sendWhisper(String targetId, String message) {
        sent.incrementAndGet();
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public CompletableFuture<ConnectionStatus> status() {
        return CompletableFuture.completedFuture(new ConnectionStatus(isConnected(), false, 0));
    }

    @Override
    public boolean isConnected() {
        return !closed && connected.isDone() && !connected.isCompletedExceptionally();
    }

    @Override
    public void close() {
        closeCalls.incrementAndGet();
        closed = true;
        connected.completeExceptionally(new ConnectionException("Connection closed"));
        terminated.complete(
                new DisconnectedEvent(
                        1000,
                        DisconnectedEvent.CLIENT_DISCONNECT_REASON,
                        false,
                        ChatEvent.DISCONNECTED,
                        "",
                        System.currentTimeMillis()));
    }
}
