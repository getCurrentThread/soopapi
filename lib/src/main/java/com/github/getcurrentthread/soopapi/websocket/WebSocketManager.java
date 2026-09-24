package com.github.getcurrentthread.soopapi.websocket;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.IntToLongFunction;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.net.ssl.SSLContext;

import com.github.getcurrentthread.soopapi.config.SOOPChatConfig;
import com.github.getcurrentthread.soopapi.constant.SOOPConstants;
import com.github.getcurrentthread.soopapi.decoder.MessageDispatcher;
import com.github.getcurrentthread.soopapi.decoder.message.JoinChannelDecoder;
import com.github.getcurrentthread.soopapi.event.ChatEvent;
import com.github.getcurrentthread.soopapi.event.EventEmitter;
import com.github.getcurrentthread.soopapi.event.model.BaseEvent;
import com.github.getcurrentthread.soopapi.event.model.DisconnectedEvent;
import com.github.getcurrentthread.soopapi.event.model.JoinChannelEvent;
import com.github.getcurrentthread.soopapi.event.model.ReconnectedEvent;
import com.github.getcurrentthread.soopapi.event.model.ReconnectingEvent;
import com.github.getcurrentthread.soopapi.exception.ConnectionException;
import com.github.getcurrentthread.soopapi.model.ChannelInfo;
import com.github.getcurrentthread.soopapi.util.SOOPChatUtils;
import com.github.getcurrentthread.soopapi.util.SSLContextProvider;
import com.github.getcurrentthread.soopapi.util.SerialExecutor;

/**
 * 채팅 서버와의 WebSocket 연결 하나를 관리합니다.
 *
 * <ul>
 *   <li><b>수립</b>은 서버가 JOIN에 응답한 시점입니다. 서버는 같은 클라이언트의 이전 세션이 정리되기 전(수 초)에 온 JOIN을 조용히 무시하므로, 응답이
 *       없으면 같은 소켓에 JOIN을 주기적으로 다시 보내고, 끝내 응답이 없으면 그 시도를 실패로 처리합니다.
 *   <li><b>송신</b>은 소켓별 체인으로 직렬화됩니다. CONNECT·JOIN이 항상 먼저 나가고, 이후 송신은 앞선 송신이 끝난 뒤에 나갑니다.
 *   <li><b>ENTER_INFO</b>: 인증 연결은 JOIN 응답을 받을 때마다 그 userFlag(synAck)로 ENTER_INFO를 체인에 넣습니다. 수신
 *       스레드에서 준비 게이트를 열기 전, dispatcher에 넘기기 전에 넣으므로 {@link #ready()}나 JOIN_CHANNEL 리스너에서 보낸 메시지는 항상
 *       ENTER_INFO 뒤에 나갑니다.
 *   <li><b>소켓 식별</b>은 {@code Socket} 객체로 합니다. 교체되거나 닫힌 소켓의 콜백은 무시되고, 그 소켓의 수신 데이터는 버려집니다.
 *   <li><b>끊김 정책</b>: 수립된 소켓이 Close 프레임(1006 제외)을 받으면 연결을 끝냅니다({@link #terminated()} 정상 완료). 전송 오류,
 *       1006, 송신·ping 실패는 backoff 재연결로 복구하고, 재시도를 다 쓰면 {@link #terminated()}가 예외로 완료됩니다.
 *   <li><b>준비</b>: {@link #ready()}는 현재 소켓이 채널에 들어가 있으면 완료되고, 연결 중이거나 재연결을 기다리는 동안에는 다음 JOIN 응답까지
 *       대기하며, 연결이 끝나면 예외로 완료됩니다.
 *   <li><b>이벤트</b>: 재시도를 예약할 때마다 {@link ChatEvent#RECONNECTING}, 수립된 연결을 복구했을 때 {@link
 *       ChatEvent#RECONNECTED}를 lane에서 emit합니다.
 * </ul>
 *
 * <p>lock 안에서는 상태만 바꾸고, WebSocket 호출·future 완료·lane 제출은 lock 밖에서 합니다.
 */
public class WebSocketManager implements AutoCloseable {
    private static final Logger LOGGER = Logger.getLogger(WebSocketManager.class.getName());
    static final long INITIAL_RETRY_DELAY_MS = 2000;
    static final long MAX_RETRY_DELAY_MS = 30000;
    static final long JOIN_RESEND_INTERVAL_MS = 1000;
    static final long MAX_JOIN_WAIT_MS = 10_000;
    private static final int ABNORMAL_CLOSURE = 1006;
    private static final JoinChannelDecoder JOIN_DECODER = new JoinChannelDecoder();

    /** WebSocket용 HttpClient는 SSLContext마다 하나를 공유하며 닫지 않습니다. 스레드는 모두 데몬입니다. */
    private static final Map<SSLContext, HttpClient> SHARED_CLIENTS = new ConcurrentHashMap<>();

    /** 소켓을 여는 함수. 테스트에서 가짜 WebSocket을 주입할 때 씁니다. */
    @FunctionalInterface
    interface Opener {
        CompletableFuture<WebSocket> open(URI uri, WebSocket.Listener listener);
    }

    public record WebSocketStatus(
            boolean connected, boolean reconnecting, int retryCount, int maxRetries) {}

    private final SOOPChatConfig config;
    private final ScheduledExecutorService scheduler;
    private final SerialExecutor lane;
    private final MessageDispatcher dispatcher;
    private final EventEmitter eventEmitter;
    private final Opener opener;
    private final IntToLongFunction backoff;
    private final long sendTimeoutMs;
    private final long joinResendMs;
    private final long joinWaitMs;
    private final CompletableFuture<DisconnectedEvent> terminated = new CompletableFuture<>();

    private final ReentrantLock lock = new ReentrantLock();
    // 아래 필드는 lock으로 보호한다.
    private URI uri;
    private String connectPacket;
    private String joinPacket;
    private String chatNo;
    private CompletableFuture<Void> sequence;
    private boolean recovery;
    private int retryCount;
    private ScheduledFuture<?> retryTask;
    private Throwable lastFailure;
    private int socketSeq;
    // 준비 게이트. 채널에 들어간 소켓이 있으면 완료, 연결 중·재연결 대기 중이면 대기, 연결이 끝나면 실패 상태다.
    private CompletableFuture<Void> readyGate = new CompletableFuture<>();
    // 쓰기는 lock 안에서만, 읽기는 lock 없이도 한다.
    private volatile Socket current;
    private volatile boolean closed;

    public WebSocketManager(
            SOOPChatConfig config,
            ScheduledExecutorService scheduler,
            SerialExecutor lane,
            MessageDispatcher dispatcher,
            EventEmitter eventEmitter) {
        this(
                config,
                scheduler,
                lane,
                dispatcher,
                eventEmitter,
                defaultOpener(config),
                WebSocketManager::backoffMillis,
                JOIN_RESEND_INTERVAL_MS);
    }

    WebSocketManager(
            SOOPChatConfig config,
            ScheduledExecutorService scheduler,
            SerialExecutor lane,
            MessageDispatcher dispatcher,
            EventEmitter eventEmitter,
            Opener opener,
            IntToLongFunction backoff,
            long joinResendMs) {
        this.config = Objects.requireNonNull(config, "config");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
        this.lane = Objects.requireNonNull(lane, "lane");
        this.dispatcher = Objects.requireNonNull(dispatcher, "dispatcher");
        this.eventEmitter = Objects.requireNonNull(eventEmitter, "eventEmitter");
        this.opener = Objects.requireNonNull(opener, "opener");
        this.backoff = Objects.requireNonNull(backoff, "backoff");
        this.sendTimeoutMs = config.getConnectionTimeout().toMillis();
        this.joinResendMs = joinResendMs;
        this.joinWaitMs = Math.min(sendTimeoutMs, MAX_JOIN_WAIT_MS);
    }

    private static Opener defaultOpener(SOOPChatConfig config) {
        SSLContext ssl =
                config.getSSLContext() != null
                        ? config.getSSLContext()
                        : SSLContextProvider.getInstance();
        HttpClient client =
                SHARED_CLIENTS.computeIfAbsent(
                        ssl, s -> HttpClient.newBuilder().sslContext(s).build());
        return (target, listener) ->
                client.newWebSocketBuilder()
                        .subprotocols("chat")
                        .connectTimeout(config.getConnectionTimeout())
                        .buildAsync(target, listener);
    }

    /** {@code attempt}번째 재시도 전 대기 시간. 2초에서 두 배씩 늘어 30초에서 멈춘다. */
    static long backoffMillis(int attempt) {
        int shift = Math.clamp(attempt - 1, 0, 16);
        return Math.min(MAX_RETRY_DELAY_MS, INITIAL_RETRY_DELAY_MS << shift);
    }

    /**
     * 채널에 연결합니다. 실패하면 backoff로 재시도합니다.
     *
     * @return 연결이 수립되면(서버가 JOIN에 응답하면) 완료되고, 재시도를 다 쓰거나 닫히면 예외로 완료되는 future. 이미 진행 중인 연결이 있으면 그 결과를
     *     따릅니다.
     */
    public CompletableFuture<Void> connect(ChannelInfo channelInfo) {
        URI target;
        try {
            target = buildWebSocketUri(channelInfo);
        } catch (ConnectionException e) {
            return CompletableFuture.failedFuture(e);
        } catch (Exception e) {
            return CompletableFuture.failedFuture(
                    new ConnectionException("Invalid chat server address", e));
        }
        String authTicket = config.isAuthenticated() ? config.getAuthCookie().authTicket() : null;
        String uuid = config.isAuthenticated() ? config.getAuthCookie().au() : null;

        Runnable open;
        CompletableFuture<Void> seq;
        lock.lock();
        try {
            if (closed) {
                return CompletableFuture.failedFuture(closedException());
            }
            if (sequence != null) {
                return sequence.copy();
            }
            Socket sock = current;
            if (sock != null && sock.ready) {
                return CompletableFuture.completedFuture(null);
            }
            uri = target;
            connectPacket = WebSocketPacketBuilder.createConnectPacket(authTicket);
            joinPacket = WebSocketPacketBuilder.createJoinPacket(channelInfo, authTicket, uuid);
            chatNo = channelInfo.CHATNO();
            seq = sequence = new CompletableFuture<>();
            recovery = false;
            open = startAttempt();
        } finally {
            lock.unlock();
        }
        open.run();
        return seq.copy();
    }

    /**
     * 현재 소켓을 버리고 새로 연결합니다. 이미 재연결 중이면 그 결과를 따릅니다.
     *
     * @return 새 소켓이 수립되면 완료되는 future
     */
    public CompletableFuture<Void> reconnect() {
        List<Runnable> after = new ArrayList<>();
        CompletableFuture<Void> seq;
        lock.lock();
        try {
            if (closed) {
                return CompletableFuture.failedFuture(closedException());
            }
            if (uri == null) {
                return CompletableFuture.failedFuture(
                        new IllegalStateException(
                                "No previous channel info available for reconnection"));
            }
            if (sequence != null) {
                return sequence.copy();
            }
            Socket sock = current;
            seq = sequence = new CompletableFuture<>();
            recovery = true;
            if (sock != null) {
                if (sock.ready) {
                    resetReady();
                }
                WebSocket old = retire(sock);
                if (old != null) {
                    after.add(() -> closeGracefully(old));
                }
            }
            ReconnectingEvent event =
                    new ReconnectingEvent(
                            retryCount + 1,
                            config.getMaxRetryAttempts(),
                            0L,
                            ChatEvent.RECONNECTING,
                            "",
                            System.currentTimeMillis());
            after.add(() -> emitOnLane(ChatEvent.RECONNECTING, event));
            after.add(startAttempt());
        } finally {
            lock.unlock();
        }
        after.forEach(Runnable::run);
        return seq.copy();
    }

    /**
     * 연결 종료를 알리는 future. 모든 경로에서 정확히 한 번 완료됩니다.
     *
     * <ul>
     *   <li>서버가 수립된 연결을 닫거나 {@link #close()}를 호출하면 그 {@link DisconnectedEvent}로 정상 완료
     *   <li>재시도를 다 쓰면 {@link ConnectionException}으로 예외 완료
     * </ul>
     */
    public CompletableFuture<DisconnectedEvent> terminated() {
        return terminated.copy();
    }

    /**
     * 채널에 들어갔음을(서버가 JOIN에 응답했음을) 알리는 future.
     *
     * <ul>
     *   <li>현재 소켓이 채널에 들어가 있으면 이미 완료된 future
     *   <li>연결 중이거나 재연결을 기다리는 중이면 다음 JOIN 응답에서 완료
     *   <li>그 전에 연결이 끝나면({@link #close()}, 서버의 Close 프레임, 재시도 소진) {@link ConnectionException}으로 예외
     *       완료
     * </ul>
     *
     * <p>future는 lock 밖에서, 대개 JOIN 응답을 받은 수신 스레드에서 완료됩니다. 호출마다 복사본을 돌려주므로 호출자가 완료해도 내부 상태는 바뀌지
     * 않습니다.
     */
    public CompletableFuture<Void> ready() {
        CompletableFuture<Void> gate;
        lock.lock();
        try {
            gate = readyGate;
        } finally {
            lock.unlock();
        }
        return gate.copy();
    }

    /** 연결을 닫습니다. 여러 번 호출해도 안전하며, 이후 연결·송신·{@link #ready()}는 모두 실패합니다. */
    @Override
    public void close() {
        WebSocket ws = null;
        CompletableFuture<Void> seq;
        ScheduledFuture<?> task;
        Runnable failWaiters;
        lock.lock();
        try {
            if (closed) {
                return;
            }
            closed = true;
            task = retryTask;
            retryTask = null;
            seq = sequence;
            sequence = null;
            failWaiters = failReady(closedException());
            Socket sock = current;
            if (sock != null) {
                ws = retire(sock);
            }
        } finally {
            lock.unlock();
        }
        if (task != null) {
            task.cancel(false);
        }
        if (seq != null) {
            seq.completeExceptionally(closedException());
        }
        failWaiters.run();
        terminated.complete(
                new DisconnectedEvent(
                        WebSocket.NORMAL_CLOSURE,
                        DisconnectedEvent.CLIENT_DISCONNECT_REASON,
                        false,
                        ChatEvent.DISCONNECTED,
                        "",
                        System.currentTimeMillis()));
        if (ws != null) {
            closeGracefully(ws);
        }
    }

    public CompletableFuture<Void> sendChat(String message) {
        return send(() -> WebSocketPacketBuilder.createChatPacket(message));
    }

    public CompletableFuture<Void> sendWhisper(String targetId, String message) {
        return send(() -> WebSocketPacketBuilder.createWhisperPacket(targetId, message));
    }

    public CompletableFuture<WebSocketStatus> getStatus() {
        lock.lock();
        try {
            return CompletableFuture.completedFuture(
                    new WebSocketStatus(
                            isConnected(),
                            sequence != null,
                            retryCount,
                            config.getMaxRetryAttempts()));
        } finally {
            lock.unlock();
        }
    }

    /** 채널에 들어간(서버가 JOIN에 응답한) 소켓이 살아 있으면 true. */
    public boolean isConnected() {
        Socket sock = current;
        return !closed && sock != null && sock.ready;
    }

    /** 현재 소켓에서 ping을 한 번 보냅니다. 테스트용입니다. */
    void pingNow() {
        Socket sock = current;
        if (sock != null) {
            tickPing(sock);
        }
    }

    // ---- 내부 구현 ----

    private interface PacketSupplier {
        String get();
    }

    private CompletableFuture<Void> send(PacketSupplier packetSupplier) {
        String packet;
        try {
            packet = packetSupplier.get();
        } catch (RuntimeException e) {
            return CompletableFuture.failedFuture(e);
        }
        Link link;
        lock.lock();
        try {
            Socket sock = current;
            if (closed || sock == null || sock.ws == null) {
                return CompletableFuture.failedFuture(
                        new IllegalStateException("WebSocket is not connected"));
            }
            link = enqueue(sock, packet);
        } finally {
            lock.unlock();
        }
        link.start();
        return link.done.copy();
    }

    /** lock 안에서 호출. 새 소켓을 현재 소켓으로 등록하고, lock 밖에서 실행할 여는 작업을 돌려준다. */
    private Runnable startAttempt() {
        Socket sock = new Socket(++socketSeq);
        current = sock;
        URI target = uri;
        LOGGER.fine(() -> "Opening WebSocket #" + sock.id + ": " + target);
        return () -> {
            CompletableFuture<WebSocket> opening;
            try {
                opening = opener.open(target, sock.listener);
            } catch (RuntimeException e) {
                opening = CompletableFuture.failedFuture(e);
            }
            opening.whenComplete((ws, err) -> onOpened(sock, ws, err));
        };
    }

    private void onOpened(Socket sock, WebSocket ws, Throwable err) {
        List<Runnable> after = new ArrayList<>();
        lock.lock();
        try {
            if (sock != current || closed) {
                // 교체됐거나 닫힌 시도에서 늦게 열린 소켓은 버린다.
                if (ws != null) {
                    after.add(ws::abort);
                }
            } else if (err != null) {
                LOGGER.log(Level.WARNING, "WebSocket #" + sock.id + " failed to open", err);
                attemptFailed(sock, err, after);
            } else {
                sock.ws = ws;
                // ws를 공개하는 임계구역에서 CONNECT·JOIN을 먼저 체인에 넣어, 어떤 송신도 이보다 앞서지 않게 한다.
                Link connect = enqueue(sock, connectPacket);
                Link join = enqueue(sock, joinPacket);
                after.add(connect::start);
                after.add(join::start);
                after.add(() -> armJoinTimer(sock));
            }
        } finally {
            lock.unlock();
        }
        after.forEach(Runnable::run);
    }

    /** JOIN 응답을 기다리는 동안 JOIN을 주기적으로 다시 보내고, 기한을 넘기면 시도를 실패로 처리하는 타이머를 건다. */
    private void armJoinTimer(Socket sock) {
        ScheduledFuture<?> task;
        try {
            task =
                    scheduler.scheduleWithFixedDelay(
                            () -> onJoinTimer(sock),
                            joinResendMs,
                            joinResendMs,
                            TimeUnit.MILLISECONDS);
        } catch (RejectedExecutionException e) {
            transportFailure(sock, e);
            return;
        }
        boolean stale;
        lock.lock();
        try {
            stale = sock != current || closed || sock.ready;
            if (!stale) {
                sock.joinTimer = task;
            }
        } finally {
            lock.unlock();
        }
        if (stale) {
            task.cancel(false);
        }
    }

    private void onJoinTimer(Socket sock) {
        Link resend = null;
        List<Runnable> after = new ArrayList<>();
        lock.lock();
        try {
            if (sock != current || closed || sock.ready) {
                return;
            }
            long waited = ++sock.joinTicks * joinResendMs;
            if (waited >= joinWaitMs) {
                LOGGER.warning(
                        "WebSocket #" + sock.id + ": no JOIN reply within " + joinWaitMs + "ms");
                attemptFailed(
                        sock,
                        new ConnectionException(
                                "Server did not answer JOIN within " + joinWaitMs + "ms"),
                        after);
            } else {
                resend = enqueue(sock, joinPacket);
            }
        } finally {
            lock.unlock();
        }
        if (resend != null) {
            LOGGER.fine(() -> "Re-sending JOIN on WebSocket #" + sock.id);
            resend.start();
        }
        after.forEach(Runnable::run);
    }

    /**
     * 서버가 JOIN에 응답했다(수신 스레드). 인증 연결이면 ENTER_INFO를 먼저 체인에 넣은 뒤 수립 처리를 한다. 준비 게이트는 그 뒤에 열리므로, {@link
     * #ready()}를 기다리던 쪽이 곧바로 보낸 메시지도 ENTER_INFO 뒤에 나간다.
     */
    private void onJoinReply(Socket sock, String reply) {
        if (config.isAuthenticated()) {
            queueEnterInfo(sock, reply);
        }
        onReady(sock);
    }

    /** JOIN 응답의 userFlag(synAck)로 ENTER_INFO를 이 소켓의 체인에 넣는다. 다른 채널의 응답이거나 synAck가 없으면 보내지 않는다. */
    private void queueEnterInfo(Socket sock, String reply) {
        String packet;
        String replyChatNo;
        try {
            int sep = reply.indexOf(SOOPConstants.F_CHAR);
            if (!(JOIN_DECODER.decode(SOOPChatUtils.splitFields(reply, sep + 1), reply)
                    instanceof JoinChannelEvent join)) {
                return;
            }
            String synAck = join.userFlag();
            if (synAck == null || synAck.isEmpty()) {
                return;
            }
            packet = WebSocketPacketBuilder.createEnterInfoPacket(synAck);
            replyChatNo = join.chatNo();
        } catch (RuntimeException e) {
            LOGGER.log(Level.WARNING, "Failed to build ENTER_INFO", e);
            return;
        }
        Link link;
        lock.lock();
        try {
            if (sock != current
                    || closed
                    || sock.ws == null
                    || !Objects.equals(chatNo, replyChatNo)) {
                return;
            }
            link = enqueue(sock, packet);
        } finally {
            lock.unlock();
        }
        link.start();
    }

    /** 소켓의 첫 JOIN 응답이면 수립 처리를 한다. 채널에 들어간 건강한 세션이므로 재시도 횟수도 되돌린다. */
    private void onReady(Socket sock) {
        CompletableFuture<Void> seq;
        CompletableFuture<Void> gate;
        ReconnectedEvent reconnected = null;
        lock.lock();
        try {
            if (sock != current || closed || sock.ready) {
                return;
            }
            sock.ready = true;
            cancelJoinTimer(sock);
            sock.ping = schedulePing(sock);
            seq = sequence;
            sequence = null;
            gate = readyGate;
            if (recovery) {
                reconnected =
                        new ReconnectedEvent(
                                Math.max(1, retryCount),
                                ChatEvent.RECONNECTED,
                                "",
                                System.currentTimeMillis());
            }
            recovery = false;
            retryCount = 0;
        } finally {
            lock.unlock();
        }
        LOGGER.info("WebSocket #" + sock.id + " established");
        if (reconnected != null) {
            emitOnLane(ChatEvent.RECONNECTED, reconnected);
        }
        if (seq != null) {
            seq.complete(null);
        }
        // lock을 놓은 뒤라 그 사이에 이 소켓이 끊겼을 수 있다. 끊김 처리는 sock.ready를 보고 새 게이트를 두므로,
        // 여기서 옛 게이트를 완료해도 이후 호출자는 다음 JOIN을 기다린다.
        gate.complete(null);
    }

    private ScheduledFuture<?> schedulePing(Socket sock) {
        long interval = config.getPingIntervalSeconds();
        try {
            return scheduler.scheduleWithFixedDelay(
                    () -> tickPing(sock), interval, interval, TimeUnit.SECONDS);
        } catch (RejectedExecutionException e) {
            LOGGER.log(Level.WARNING, "Scheduler rejected ping task", e);
            return null;
        }
    }

    /** 스케줄러 스레드를 막지 않는다. ping은 송신 체인에 넣고, 실패는 체인이 전송 장애로 처리한다. */
    private void tickPing(Socket sock) {
        Link link;
        lock.lock();
        try {
            if (sock != current || closed || sock.ws == null) {
                return;
            }
            link = enqueue(sock, WebSocketPacketBuilder.createPingPacket());
        } finally {
            lock.unlock();
        }
        LOGGER.fine("Sending ping packet");
        link.start();
    }

    private void onServerClosed(Socket sock, int statusCode, String reason) {
        if (statusCode == ABNORMAL_CLOSURE) {
            transportFailure(
                    sock,
                    new ConnectionException("Connection closed abnormally (1006): " + reason));
            return;
        }
        List<Runnable> after = new ArrayList<>();
        lock.lock();
        try {
            if (sock != current || closed) {
                return;
            }
            if (!sock.ready) {
                attemptFailed(
                        sock,
                        new ConnectionException(
                                "Server closed during handshake: " + statusCode + " " + reason),
                        after);
            } else {
                LOGGER.info(
                        "WebSocket #"
                                + sock.id
                                + " closed by server: "
                                + statusCode
                                + " "
                                + reason);
                closed = true;
                retire(sock);
                after.add(
                        failReady(
                                new ConnectionException(
                                        "Connection closed by server: "
                                                + statusCode
                                                + " "
                                                + reason)));
                DisconnectedEvent event =
                        new DisconnectedEvent(
                                statusCode,
                                reason,
                                false,
                                ChatEvent.DISCONNECTED,
                                "",
                                System.currentTimeMillis());
                after.add(() -> terminated.complete(event));
            }
        } finally {
            lock.unlock();
        }
        after.forEach(Runnable::run);
    }

    private void transportFailure(Socket sock, Throwable cause) {
        List<Runnable> after = new ArrayList<>();
        lock.lock();
        try {
            if (sock != current || closed) {
                return;
            }
            if (!sock.ready) {
                attemptFailed(sock, cause, after);
            } else {
                LOGGER.log(Level.WARNING, "WebSocket #" + sock.id + " failed; reconnecting", cause);
                resetReady();
                WebSocket ws = retire(sock);
                if (ws != null) {
                    after.add(ws::abort);
                }
                lastFailure = cause;
                sequence = new CompletableFuture<>();
                recovery = true;
                scheduleRetry(after);
            }
        } finally {
            lock.unlock();
        }
        after.forEach(Runnable::run);
    }

    /** lock 안에서 호출. 수립 전 소켓의 실패를 그 시도의 실패로 처리한다. */
    private void attemptFailed(Socket sock, Throwable cause, List<Runnable> after) {
        WebSocket ws = retire(sock);
        if (ws != null) {
            after.add(ws::abort);
        }
        lastFailure = cause;
        scheduleRetry(after);
    }

    /** lock 안에서 호출. 재시도 횟수를 올리고, 한도를 넘으면 소진 처리, 아니면 RECONNECTING 뒤에 재시도를 예약한다. */
    private void scheduleRetry(List<Runnable> after) {
        int attempt = ++retryCount;
        if (attempt > config.getMaxRetryAttempts()) {
            exhaust(after);
            return;
        }
        long delay = backoff.applyAsLong(attempt);
        CompletableFuture<Void> token = sequence;
        ReconnectingEvent event =
                new ReconnectingEvent(
                        attempt,
                        config.getMaxRetryAttempts(),
                        delay,
                        ChatEvent.RECONNECTING,
                        "",
                        System.currentTimeMillis());
        LOGGER.fine(
                () ->
                        "Retry %d of %d in %dms"
                                .formatted(attempt, config.getMaxRetryAttempts(), delay));
        // RECONNECTING을 lane에 먼저 넣은 뒤 예약해야, 재시도 결과 이벤트가 RECONNECTING보다 앞서지 않는다.
        after.add(() -> emitOnLane(ChatEvent.RECONNECTING, event));
        after.add(() -> armRetry(token, delay));
    }

    private void armRetry(CompletableFuture<Void> token, long delay) {
        ScheduledFuture<?> task;
        try {
            task = scheduler.schedule(() -> runRetry(token), delay, TimeUnit.MILLISECONDS);
        } catch (RejectedExecutionException e) {
            List<Runnable> after = new ArrayList<>();
            lock.lock();
            try {
                if (!closed && sequence == token) {
                    lastFailure = e;
                    exhaust(after);
                }
            } finally {
                lock.unlock();
            }
            after.forEach(Runnable::run);
            return;
        }
        boolean stale;
        lock.lock();
        try {
            stale = closed || sequence != token;
            if (!stale) {
                retryTask = task;
            }
        } finally {
            lock.unlock();
        }
        if (stale) {
            task.cancel(false);
        }
    }

    private void runRetry(CompletableFuture<Void> token) {
        Runnable open;
        lock.lock();
        try {
            if (closed || sequence != token || current != null) {
                return;
            }
            retryTask = null;
            open = startAttempt();
        } finally {
            lock.unlock();
        }
        open.run();
    }

    /** lock 안에서 호출. 재시도를 다 썼으므로 연결을 끝낸다. */
    private void exhaust(List<Runnable> after) {
        closed = true;
        CompletableFuture<Void> seq = sequence;
        sequence = null;
        ConnectionException failure =
                new ConnectionException(
                        "Connection failed after " + config.getMaxRetryAttempts() + " retries",
                        lastFailure);
        LOGGER.log(Level.SEVERE, "Max retry attempts reached", lastFailure);
        Runnable failWaiters = failReady(failure);
        after.add(
                () -> {
                    if (seq != null) {
                        seq.completeExceptionally(failure);
                    }
                    failWaiters.run();
                    terminated.completeExceptionally(failure);
                });
    }

    /**
     * lock 안에서 호출. 채널에 들어가 있던 소켓을 복구하려고 내릴 때 새 게이트를 둔다. 게이트가 이미 완료됐는지가 아니라 소켓의 {@code ready}로 판단해야
     * 한다. {@link #onReady}는 lock을 놓은 뒤에 게이트를 완료하므로, 그 사이에 끊기면 게이트는 아직 대기 상태이기 때문이다.
     */
    private void resetReady() {
        readyGate = new CompletableFuture<>();
    }

    /** lock 안에서 호출. 연결이 끝났으므로 이후의 {@link #ready()}가 곧바로 실패하게 하고, 기다리던 쪽을 lock 밖에서 실패시킬 작업을 돌려준다. */
    private Runnable failReady(ConnectionException failure) {
        CompletableFuture<Void> gate = readyGate;
        readyGate = CompletableFuture.failedFuture(failure);
        return () -> gate.completeExceptionally(failure);
    }

    /** lock 안에서 호출. 소켓을 현재 소켓에서 내리고 ping을 멈추며 수신을 끊는다. 정리할 ws를 돌려준다. */
    private WebSocket retire(Socket sock) {
        if (sock.ping != null) {
            sock.ping.cancel(false);
            sock.ping = null;
        }
        cancelJoinTimer(sock);
        sock.listener.detach();
        if (current == sock) {
            current = null;
        }
        return sock.ws;
    }

    /** lock 안에서 호출. */
    private static void cancelJoinTimer(Socket sock) {
        if (sock.joinTimer != null) {
            sock.joinTimer.cancel(false);
            sock.joinTimer = null;
        }
    }

    /** lock 안에서 호출. 송신 체인 끝에 고리를 붙인다. 실제 송신은 lock 밖에서 {@link Link#start()}로 시작한다. */
    private Link enqueue(Socket sock, String packet) {
        Link link = new Link(sock, sock.ws, sock.tail, packet);
        sock.tail = link.done;
        return link;
    }

    private void closeGracefully(WebSocket ws) {
        CompletableFuture<WebSocket> closing;
        try {
            closing = ws.sendClose(WebSocket.NORMAL_CLOSURE, "");
        } catch (RuntimeException e) {
            ws.abort();
            return;
        }
        closing.copy()
                .orTimeout(sendTimeoutMs, TimeUnit.MILLISECONDS)
                .whenComplete((r, e) -> ws.abort());
    }

    private void emitOnLane(ChatEvent type, BaseEvent event) {
        lane.execute(() -> eventEmitter.emit(type, event));
    }

    private ConnectionException closedException() {
        return new ConnectionException("WebSocket connection is closed");
    }

    private URI buildWebSocketUri(ChannelInfo channelInfo) throws java.net.URISyntaxException {
        int portNumber;
        try {
            portNumber = Integer.parseInt(channelInfo.CHPT());
        } catch (NumberFormatException e) {
            throw new ConnectionException("Invalid port number format: " + channelInfo.CHPT(), e);
        }
        return new URI(
                "wss",
                null,
                channelInfo.CHDOMAIN(),
                portNumber,
                "/Websocket/" + config.getBid(),
                null,
                null);
    }

    /** WebSocket 하나의 상태. 필드는 lock으로 보호하며, volatile 필드만 lock 없이 읽는다. */
    private final class Socket implements WebSocketListener.Callbacks {
        final int id;
        final WebSocketListener listener;
        WebSocket ws;
        CompletableFuture<Void> tail = CompletableFuture.completedFuture(null);
        ScheduledFuture<?> ping;
        ScheduledFuture<?> joinTimer;
        int joinTicks;
        volatile boolean ready;

        Socket(int id) {
            this.id = id;
            this.listener = new WebSocketListener(dispatcher, lane, this);
        }

        @Override
        public void onJoinReply(String reply) {
            WebSocketManager.this.onJoinReply(this, reply);
        }

        @Override
        public void onClosed(int statusCode, String reason) {
            onServerClosed(this, statusCode, reason);
        }

        @Override
        public void onFailed(Throwable error) {
            transportFailure(this, error);
        }
    }

    /** 송신 체인의 고리 하나. 앞 고리가 끝나면(성공이든 실패든) 자기 패킷을 보낸다. */
    private final class Link {
        final Socket sock;
        final WebSocket ws;
        final CompletableFuture<Void> prev;
        final String packet;
        final CompletableFuture<Void> done = new CompletableFuture<>();

        Link(Socket sock, WebSocket ws, CompletableFuture<Void> prev, String packet) {
            this.sock = sock;
            this.ws = ws;
            this.prev = prev;
            this.packet = packet;
        }

        void start() {
            prev.whenComplete((r, e) -> send());
        }

        private void send() {
            CompletableFuture<WebSocket> sent;
            try {
                sent = ws.sendText(packet, true);
            } catch (RuntimeException e) {
                sent = CompletableFuture.failedFuture(e);
            }
            sent.copy()
                    .orTimeout(sendTimeoutMs, TimeUnit.MILLISECONDS)
                    .whenComplete(
                            (w, e) -> {
                                if (e == null) {
                                    done.complete(null);
                                } else {
                                    done.completeExceptionally(e);
                                    transportFailure(sock, e);
                                }
                            });
        }
    }
}
