package com.github.getcurrentthread.soopapi.connection;

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Level;
import java.util.logging.Logger;

import com.github.getcurrentthread.soopapi.api.SOOPHttpClient;
import com.github.getcurrentthread.soopapi.api.SOOPLive;
import com.github.getcurrentthread.soopapi.config.SOOPChatConfig;
import com.github.getcurrentthread.soopapi.decoder.MessageDispatcher;
import com.github.getcurrentthread.soopapi.decoder.factory.DefaultMessageDecoderFactory;
import com.github.getcurrentthread.soopapi.decoder.message.IMessageDecoder;
import com.github.getcurrentthread.soopapi.event.ChatEvent;
import com.github.getcurrentthread.soopapi.event.EventEmitter;
import com.github.getcurrentthread.soopapi.event.model.DisconnectedEvent;
import com.github.getcurrentthread.soopapi.event.model.JoinChannelEvent;
import com.github.getcurrentthread.soopapi.exception.ConnectionException;
import com.github.getcurrentthread.soopapi.model.ChannelInfo;
import com.github.getcurrentthread.soopapi.model.ConnectionStatus;
import com.github.getcurrentthread.soopapi.util.SOOPChatUtils;
import com.github.getcurrentthread.soopapi.util.SerialExecutor;
import com.github.getcurrentthread.soopapi.websocket.WebSocketManager;

/**
 * SOOP 채팅 서버와의 연결 하나. 방송 정보를 조회한 뒤 WebSocket으로 연결하고, 수신 패킷을 클라이언트의 lane으로 전달합니다.
 *
 * <p>인증된 연결은 JOIN_CHANNEL을 받을 때마다 ENTER_INFO를 보냅니다. 이 처리는 연결 자신의 dispatcher에 묶여 있어, 연결이 바뀌어도 쌓이거나
 * 새지 않습니다.
 */
public final class SOOPConnection implements ChatConnection {
    private static final Logger LOGGER = Logger.getLogger(SOOPConnection.class.getName());
    private static final Map<ChatEvent, IMessageDecoder> SHARED_DECODERS =
            new DefaultMessageDecoderFactory().createDecoders();

    private final SOOPChatConfig config;
    private final MessageDispatcher messageDispatcher;
    private final WebSocketManager webSocketManager;
    private final AtomicReference<CompletableFuture<Void>> connectFuture = new AtomicReference<>();
    private final CompletableFuture<DisconnectedEvent> terminated = new CompletableFuture<>();
    private final AtomicBoolean closed = new AtomicBoolean();
    private volatile ChannelInfo channelInfo;

    public SOOPConnection(
            SOOPChatConfig config,
            ScheduledExecutorService scheduler,
            EventEmitter eventEmitter,
            SerialExecutor lane) {
        this.config = Objects.requireNonNull(config, "config");
        this.messageDispatcher = new MessageDispatcher(SHARED_DECODERS, lane, eventEmitter);
        this.webSocketManager =
                new WebSocketManager(config, scheduler, lane, messageDispatcher, eventEmitter);
        if (config.isAuthenticated()) {
            messageDispatcher.setJoinChannelHook(this::sendEnterInfo);
        }
    }

    private void sendEnterInfo(JoinChannelEvent event) {
        ChannelInfo info = channelInfo;
        if (info != null && !Objects.equals(info.CHATNO(), event.chatNo())) {
            return;
        }
        String synAck = event.userFlag();
        if (synAck != null && !synAck.isEmpty()) {
            webSocketManager
                    .sendEnterInfo(synAck)
                    .exceptionally(
                            e -> {
                                LOGGER.log(Level.WARNING, "Failed to send ENTER_INFO", e);
                                return null;
                            });
        }
    }

    @Override
    public CompletableFuture<Void> connect() {
        CompletableFuture<Void> result = new CompletableFuture<>();
        if (!connectFuture.compareAndSet(null, result)) {
            return connectFuture.get().copy();
        }
        if (closed.get()) {
            result.completeExceptionally(closedException());
            return result.copy();
        }

        // 조회용 HTTP 클라이언트는 연결마다 두어 쿠키를 섞지 않고, 조회가 끝나면 바로 정리한다.
        SOOPHttpClient httpClient = new SOOPHttpClient(config.getConnectionTimeout());
        SOOPLive soopLive = new SOOPLive(httpClient);
        CompletableFuture<String> bno =
                config.getBno() != null
                        ? CompletableFuture.completedFuture(config.getBno())
                        : soopLive.getBno(config.getBid());
        bno
                // authCookie를 넘겨야 인증된 FTK를 받는다. 익명 FTK와 인증 CONNECT를 섞으면 서버가 JOIN을 조용히 거부한다.
                .thenCompose(b -> soopLive.detail(config.getBid(), b, config.getAuthCookie()))
                .thenApply(soopLive::toChannelInfo)
                .thenCompose(
                        info -> {
                            validate(info);
                            channelInfo = info;
                            if (closed.get()) {
                                throw new CompletionException(closedException());
                            }
                            return webSocketManager.connect(info);
                        })
                .whenComplete(
                        (unused, error) -> {
                            httpClient.shutdown();
                            if (error == null) {
                                result.complete(null);
                            } else {
                                // close()로 중단된 시도는 실패가 아니다.
                                LOGGER.log(
                                        closed.get() ? Level.FINE : Level.WARNING,
                                        "Connection failed: " + config.getBid(),
                                        error);
                                result.completeExceptionally(toConnectionException(error));
                            }
                            settle(error);
                        });
        return result.copy();
    }

    /** connect 결과가 정해진 뒤 한 번만 호출된다. 이후의 종료 신호를 {@link #terminated}로 옮긴다. */
    private void settle(Throwable connectError) {
        if (connectError == null) {
            webSocketManager
                    .terminated()
                    .whenComplete(
                            (event, error) -> {
                                if (error == null) {
                                    terminated.complete(event);
                                } else {
                                    terminated.completeExceptionally(toConnectionException(error));
                                }
                            });
        } else if (closed.get()) {
            terminated.complete(clientDisconnectEvent());
        } else {
            terminated.completeExceptionally(toConnectionException(connectError));
        }
    }

    private static void validate(ChannelInfo info) {
        if (info.CHPT() == null || info.CHPT().isBlank()) {
            throw new CompletionException(
                    new ConnectionException("Invalid channel port: " + info.CHPT()));
        }
        if (info.CHDOMAIN() == null || info.CHDOMAIN().isBlank()) {
            throw new CompletionException(
                    new ConnectionException("Invalid channel domain: " + info.CHDOMAIN()));
        }
    }

    @Override
    public CompletableFuture<Void> reconnect() {
        return webSocketManager
                .reconnect()
                .handle(
                        (unused, error) -> {
                            if (error != null) {
                                throw new CompletionException(toConnectionException(error));
                            }
                            return null;
                        });
    }

    @Override
    public CompletableFuture<DisconnectedEvent> terminated() {
        return terminated.copy();
    }

    @Override
    public CompletableFuture<Void> sendChat(String message) {
        return webSocketManager.sendChat(message);
    }

    @Override
    public CompletableFuture<Void> sendWhisper(String targetId, String message) {
        return webSocketManager.sendWhisper(targetId, message);
    }

    @Override
    public CompletableFuture<ConnectionStatus> status() {
        return webSocketManager
                .getStatus()
                .thenApply(
                        ws ->
                                new ConnectionStatus(
                                        ws.connected(), ws.reconnecting(), ws.retryCount()));
    }

    @Override
    public boolean isConnected() {
        return webSocketManager.isConnected();
    }

    /**
     * 연결을 닫습니다. 진행 중인 연결 시도는 즉시 실패하고, {@link #terminated()}는 호출 스레드에서 바로 완료됩니다. 아직 전달되지 않은 수신 패킷은
     * 버려집니다.
     */
    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        messageDispatcher.deactivate();
        webSocketManager.close();
        CompletableFuture<Void> pending = connectFuture.get();
        if (pending != null) {
            pending.completeExceptionally(closedException());
        }
        terminated.complete(clientDisconnectEvent());
    }

    public ChannelInfo getChannelInfo() {
        return channelInfo;
    }

    private static DisconnectedEvent clientDisconnectEvent() {
        return new DisconnectedEvent(
                1000,
                DisconnectedEvent.CLIENT_DISCONNECT_REASON,
                false,
                ChatEvent.DISCONNECTED,
                "",
                System.currentTimeMillis());
    }

    private static ConnectionException closedException() {
        return new ConnectionException("Connection closed");
    }

    private ConnectionException toConnectionException(Throwable error) {
        Throwable cause = SOOPChatUtils.unwrapCompletionException(error);
        return cause instanceof ConnectionException ce
                ? ce
                : new ConnectionException("Cannot connect to channel: " + config.getBid(), cause);
    }
}
