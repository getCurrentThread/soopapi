package com.github.getcurrentthread.soopapi.connection;

import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.locks.ReentrantLock;
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

public class SOOPConnection implements AutoCloseable {
    private static final Logger LOGGER = Logger.getLogger(SOOPConnection.class.getName());
    private static final Map<ChatEvent, IMessageDecoder> SHARED_DECODERS =
            new DefaultMessageDecoderFactory().createDecoders();

    private final SOOPChatConfig config;
    private final ExecutorService executor;
    private final SOOPHttpClient httpClient;
    private final SOOPLive soopLive;
    private final MessageDispatcher messageDispatcher;
    private final WebSocketManager webSocketManager;
    private final ReentrantLock connectionLock = new ReentrantLock();

    private volatile ChannelInfo channelInfo;
    private volatile boolean isConnected;

    public SOOPConnection(
            SOOPChatConfig config,
            ExecutorService messageProcessor,
            ScheduledExecutorService scheduler,
            EventEmitter eventEmitter) {
        this.config = config;
        this.executor = messageProcessor;
        this.httpClient = new SOOPHttpClient(config.getConnectionTimeout());
        this.soopLive = new SOOPLive(httpClient);

        // 연결마다 lane 하나: 데이터와 수명주기 이벤트가 도착 순서대로, 겹치지 않게 전달된다.
        SerialExecutor lane = new SerialExecutor(messageProcessor);
        this.messageDispatcher = new MessageDispatcher(SHARED_DECODERS, lane, eventEmitter);
        this.webSocketManager =
                new WebSocketManager(config, scheduler, lane, messageDispatcher, eventEmitter);

        registerEnterInfoHandler(eventEmitter);

        // 경과 조치: SOOPChatClient가 아직 once(DISCONNECTED)로 세션 종료를 감지하므로 종료를 이벤트로 바꿔 준다.
        webSocketManager
                .terminated()
                .whenCompleteAsync(
                        (event, error) -> {
                            isConnected = false;
                            eventEmitter.emit(
                                    ChatEvent.DISCONNECTED,
                                    event != null ? event : errorEvent(error));
                        },
                        lane);
    }

    private static DisconnectedEvent errorEvent(Throwable error) {
        Throwable cause = SOOPChatUtils.unwrapCompletionException(error);
        String message = cause.getMessage() != null ? cause.getMessage() : cause.toString();
        return new DisconnectedEvent(
                -1, message, true, ChatEvent.DISCONNECTED, "", System.currentTimeMillis());
    }

    private void registerEnterInfoHandler(EventEmitter eventEmitter) {
        if (config.isAuthenticated()) {
            eventEmitter.onInternal(
                    ChatEvent.JOIN_CHANNEL,
                    (JoinChannelEvent event) -> {
                        if (channelInfo != null && !channelInfo.CHATNO().equals(event.chatNo())) {
                            return;
                        }
                        String synAck = event.userFlag();
                        if (synAck != null && !synAck.isEmpty()) {
                            webSocketManager
                                    .sendEnterInfo(synAck)
                                    .exceptionally(
                                            e -> {
                                                LOGGER.log(
                                                        Level.WARNING,
                                                        "Failed to send ENTER_INFO",
                                                        e);
                                                return null;
                                            });
                        }
                    });
        }
    }

    public CompletableFuture<Void> connect() {
        return CompletableFuture.runAsync(
                () -> {
                    connectionLock.lock();
                    try {
                        if (isConnected) {
                            LOGGER.fine("Already connected.");
                            return;
                        }
                    } finally {
                        connectionLock.unlock();
                    }

                    try {
                        LOGGER.fine(() -> "Fetching channel info: " + config.getBid());
                        String bno =
                                config.getBno() != null
                                        ? config.getBno()
                                        : soopLive.getBno(config.getBid()).join();

                        // Pass authCookie so the live-detail HTTP call is authenticated.
                        // Without it, the server returns an anonymous FTK; combined with
                        // an authenticated CONNECT packet, the chat server silently rejects
                        // the JOIN packet (no JOIN_CHANNEL ack ever arrives).
                        channelInfo =
                                soopLive.toChannelInfo(
                                        soopLive.detail(
                                                        config.getBid(),
                                                        bno,
                                                        config.getAuthCookie())
                                                .join());
                        LOGGER.fine(() -> "Channel info received: " + channelInfo);

                        if (channelInfo.CHPT() == null || channelInfo.CHPT().trim().isEmpty()) {
                            throw new ConnectionException(
                                    "Invalid channel port: " + channelInfo.CHPT());
                        }

                        if (channelInfo.CHDOMAIN() == null
                                || channelInfo.CHDOMAIN().trim().isEmpty()) {
                            throw new ConnectionException(
                                    "Invalid channel domain: " + channelInfo.CHDOMAIN());
                        }

                        webSocketManager.connect(channelInfo).join();

                        connectionLock.lock();
                        try {
                            isConnected = true;
                        } finally {
                            connectionLock.unlock();
                        }
                    } catch (Exception e) {
                        LOGGER.log(Level.SEVERE, "Connection failed", e);
                        Throwable cause = SOOPChatUtils.unwrapCompletionException(e);
                        if (cause instanceof ConnectionException ce) {
                            throw new CompletionException(ce);
                        }
                        throw new CompletionException(
                                new ConnectionException("Failed to connect", cause));
                    }
                },
                executor);
    }

    public CompletableFuture<Void> reconnect() {
        return webSocketManager
                .reconnect()
                .handle(
                        (unused, error) -> {
                            if (error == null) {
                                isConnected = true;
                                return null;
                            }
                            Throwable cause = SOOPChatUtils.unwrapCompletionException(error);
                            throw new CompletionException(
                                    cause instanceof ConnectionException
                                            ? cause
                                            : new ConnectionException(
                                                    "Failed to reconnect", cause));
                        });
    }

    public CompletableFuture<Void> sendChat(String message) {
        return webSocketManager.sendChat(message);
    }

    public CompletableFuture<Void> sendWhisper(String targetId, String message) {
        return webSocketManager.sendWhisper(targetId, message);
    }

    public void disconnect() {
        messageDispatcher.deactivate();
        connectionLock.lock();
        try {
            try {
                webSocketManager.close();
            } finally {
                isConnected = false;
            }
        } finally {
            connectionLock.unlock();
        }
    }

    @Override
    public void close() {
        messageDispatcher.deactivate();
        connectionLock.lock();
        try {
            try {
                webSocketManager.close();
            } finally {
                isConnected = false;
            }
        } finally {
            connectionLock.unlock();
        }
        httpClient.close();
    }

    public CompletableFuture<ConnectionStatus> getStatus() {
        return webSocketManager
                .getStatus()
                .thenApply(
                        wsStatus ->
                                new ConnectionStatus(
                                        wsStatus.connected(),
                                        wsStatus.reconnecting(),
                                        wsStatus.retryCount()));
    }

    /**
     * 연결 활성 상태를 반환합니다. 두 값의 조합이므로 정확한 atomic snapshot이 아닌 best-effort 체크입니다.
     *
     * @return 연결이 활성 상태로 보이면 true
     */
    public boolean isConnected() {
        return isConnected && webSocketManager.isConnected();
    }

    public boolean isReconnecting() {
        return webSocketManager.getStatus().join().reconnecting();
    }

    public ChannelInfo getChannelInfo() {
        return channelInfo;
    }

    public SOOPChatConfig getConfig() {
        return config;
    }
}
