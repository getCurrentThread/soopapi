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
 *
 * <p>{@link #ready()}는 {@link #establish()}로 채널에 들어가면 완료되고, {@link #drop()}으로 끊기면 다시 대기하며, 연결이 끝나면
 * 실패합니다. future는 모두 모니터 밖에서 완료합니다.
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
    // this로 보호한다.
    private CompletableFuture<Void> readyGate = new CompletableFuture<>();

    FakeChatConnection(SOOPChatConfig config, EventEmitter emitter, SerialExecutor lane) {
        this.config = config;
        this.emitter = emitter;
        this.lane = lane;
    }

    /** 연결 수립(서버의 JOIN 응답)을 흉내 냅니다. {@link #drop()} 뒤에 다시 부르면 재연결에 성공한 것입니다. */
    public void establish() {
        connected.complete(null);
        CompletableFuture<Void> gate;
        synchronized (this) {
            gate = readyGate;
        }
        gate.complete(null);
    }

    /** 수립된 연결이 끊겨 재연결을 기다리는 상황을 흉내 냅니다. 이후 {@link #ready()}는 다시 {@link #establish()}할 때까지 대기합니다. */
    public synchronized void drop() {
        if (readyGate.isDone() && !readyGate.isCompletedExceptionally()) {
            readyGate = new CompletableFuture<>();
        }
    }

    /** 연결 실패(재시도 소진 포함)를 흉내 냅니다. */
    public void fail(String message) {
        ConnectionException error = new ConnectionException(message);
        connected.completeExceptionally(error);
        failReady(error);
        terminated.completeExceptionally(error);
    }

    /** 수립된 연결을 서버가 닫는 상황을 흉내 냅니다. */
    public void serverClose(int code, String reason) {
        failReady(new ConnectionException("Connection closed by server: " + code + " " + reason));
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

    /** {@link #terminated()} 원본에 걸린 의존 작업 수. 호출마다 작업이 쌓이는지 볼 때 씁니다. */
    public int terminatedDependents() {
        return terminated.getNumberOfDependents();
    }

    private void failReady(ConnectionException error) {
        CompletableFuture<Void> gate;
        synchronized (this) {
            gate = readyGate;
            readyGate = CompletableFuture.failedFuture(error);
        }
        gate.completeExceptionally(error);
    }

    private synchronized CompletableFuture<Void> readyGate() {
        return readyGate;
    }

    @Override
    public CompletableFuture<Void> connect() {
        connectCalls.incrementAndGet();
        return connected.copy();
    }

    @Override
    public CompletableFuture<Void> ready() {
        return readyGate().copy();
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
        CompletableFuture<Void> gate = readyGate();
        return !closed && gate.isDone() && !gate.isCompletedExceptionally();
    }

    @Override
    public void close() {
        closeCalls.incrementAndGet();
        closed = true;
        ConnectionException error = new ConnectionException("Connection closed");
        connected.completeExceptionally(error);
        failReady(error);
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
