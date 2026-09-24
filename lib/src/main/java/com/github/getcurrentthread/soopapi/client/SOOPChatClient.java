package com.github.getcurrentthread.soopapi.client;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.locks.ReentrantLock;
import java.util.logging.Level;
import java.util.logging.Logger;

import com.github.getcurrentthread.soopapi.config.SOOPChatConfig;
import com.github.getcurrentthread.soopapi.connection.ChatConnection;
import com.github.getcurrentthread.soopapi.connection.ChatConnectionFactory;
import com.github.getcurrentthread.soopapi.connection.ConnectionManager;
import com.github.getcurrentthread.soopapi.event.ChatEvent;
import com.github.getcurrentthread.soopapi.event.EventEmitter;
import com.github.getcurrentthread.soopapi.event.EventListener;
import com.github.getcurrentthread.soopapi.event.model.BaseEvent;
import com.github.getcurrentthread.soopapi.event.model.DisconnectedEvent;
import com.github.getcurrentthread.soopapi.event.model.ReconnectedEvent;
import com.github.getcurrentthread.soopapi.event.model.ReconnectingEvent;
import com.github.getcurrentthread.soopapi.exception.AuthenticationException;
import com.github.getcurrentthread.soopapi.exception.ConnectionException;
import com.github.getcurrentthread.soopapi.model.ConnectionStatus;
import com.github.getcurrentthread.soopapi.util.SOOPChatUtils;
import com.github.getcurrentthread.soopapi.util.SerialExecutor;

/**
 * SOOP 채팅 클라이언트.
 *
 * <p>{@link SOOPChatConfig}에 {@code authCookie}가 설정되지 않은 경우, 클라이언트는 익명(읽기 전용) 모드로 연결됩니다. 익명 모드에서는
 * 채팅 메시지를 수신할 수 있지만, {@link #sendChat(String)}이나 {@link #sendWhisper(String, String)}을 호출하면 {@link
 * AuthenticationException}이 발생합니다. 19금 방송은 익명으로 들어갈 수 없어, 세션이 {@link
 * com.github.getcurrentthread.soopapi.exception.AdultBroadcastException}을 원인으로 둔 {@link
 * ConnectionException}으로 끝납니다.
 *
 * <h2>세션과 이벤트</h2>
 *
 * <ul>
 *   <li>{@link #connectToChat()}으로 세션을 시작합니다. 세션은 {@link #disconnect()}, 서버의 연결 종료, 연결 실패(재시도 소진
 *       포함) 중 하나로 끝나며, 끝날 때 {@link ChatEvent#DISCONNECTED}가 <b>정확히 한 번</b> 발생합니다.
 *   <li>이벤트는 클라이언트마다 도착 순서대로 <b>한 번에 하나씩</b> 전달됩니다. 리스너가 느리면 그 클라이언트의 이벤트가 늦어집니다.
 *   <li>리스너 안에서 {@code sendChat(..).join()}이나 {@code ready().join()}처럼 기다리는 것은 안전하지만, 세션
 *       future({@link #connectToChat()}, {@link #forceReconnect()})를 기다리면 교착 상태가 됩니다.
 *   <li>메시지 전송은 채널에 들어간 뒤에 하세요. {@link #ready()}로 기다리거나 {@link ChatEvent#JOIN_CHANNEL}을 받은 뒤에 보내면
 *       됩니다.
 * </ul>
 *
 * <pre>{@code
 * chat.connectToChat();
 * chat.ready().thenRun(() -> chat.sendChat("hi"));
 * }</pre>
 */
public class SOOPChatClient implements AutoCloseable {
    private static final Logger LOGGER = Logger.getLogger(SOOPChatClient.class.getName());

    private final SOOPChatConfig config;
    private final ChatConnectionFactory factory;
    private final EventEmitter eventEmitter = new EventEmitter();
    private final SerialExecutor lane;
    private final ReentrantLock lock = new ReentrantLock();
    // lock으로 보호한다.
    private Session session;
    private boolean closed;

    /** 세션 하나. {@code connection}은 {@link #forceReconnect()}로 바뀔 수 있고, 세션은 현재 연결이 끝날 때만 끝난다. */
    private static final class Session {
        final CompletableFuture<Void> done = new CompletableFuture<>();
        final CompletableFuture<Void> publicDone = done.copy();
        ChatConnection connection;
        boolean ended;

        Session(ChatConnection connection) {
            this.connection = connection;
        }
    }

    public SOOPChatClient(SOOPChatConfig config) {
        this(config, ConnectionManager.getInstance());
    }

    /** 연결 생성 방식을 바꿔 끼울 때 씁니다(테스트, 고급 사용). */
    public SOOPChatClient(SOOPChatConfig config, ChatConnectionFactory factory) {
        validateConfig(config);
        this.config = config;
        this.factory = Objects.requireNonNull(factory, "factory");
        this.lane = factory.newLane();
    }

    private static void validateConfig(SOOPChatConfig config) {
        if (config == null) {
            throw new IllegalArgumentException("config must not be null");
        }
        if (config.getBid() == null || config.getBid().isBlank()) {
            throw new IllegalArgumentException("bid must not be null or blank");
        }
    }

    public <T extends BaseEvent> SOOPChatClient on(ChatEvent event, EventListener<T> listener) {
        eventEmitter.on(event, listener);
        return this;
    }

    public <T extends BaseEvent> SOOPChatClient once(ChatEvent event, EventListener<T> listener) {
        eventEmitter.once(event, listener);
        return this;
    }

    public <T extends BaseEvent> SOOPChatClient off(ChatEvent event, EventListener<T> listener) {
        eventEmitter.off(event, listener);
        return this;
    }

    /**
     * 비동기적으로 채팅에 연결합니다.
     *
     * <p>반환된 {@code CompletableFuture}는 세션이 <b>끝날 때</b> 완료됩니다. 사용자 종료나 서버의 연결 종료면 정상 완료, 연결 실패나 재시도
     * 소진이면 {@link ConnectionException}으로 예외 완료됩니다. 이미 세션이 진행 중이면 같은 future를 반환합니다. 채널에 들어간 시점은
     * {@link #ready()}나 {@link ChatEvent#JOIN_CHANNEL}로 확인하세요.
     *
     * @return 세션이 끝날 때 완료되는 CompletableFuture. {@link #close()} 뒤에는 실패한 future
     */
    public CompletableFuture<Void> connectToChat() {
        Session s;
        lock.lock();
        try {
            if (closed) {
                return CompletableFuture.failedFuture(closedException());
            }
            if (session != null) {
                return session.publicDone;
            }
            ChatConnection c;
            try {
                c = factory.createConnection(config, eventEmitter, lane);
            } catch (RuntimeException e) {
                return CompletableFuture.failedFuture(
                        new ConnectionException("Failed to create connection", e));
            }
            s = session = new Session(c);
        } finally {
            lock.unlock();
        }
        start(s, s.connection);
        return s.publicDone;
    }

    /** 연결을 시작하고, 그 연결의 종료를 세션 종료로 잇는다. */
    private CompletableFuture<Void> start(Session s, ChatConnection c) {
        c.terminated().whenCompleteAsync((event, error) -> onTerminated(s, c, event, error), lane);
        return c.connect();
    }

    /** lane에서 실행된다. 세션의 현재 연결이 끝났을 때만 세션을 끝낸다. */
    private void onTerminated(
            Session s, ChatConnection c, DisconnectedEvent event, Throwable error) {
        lock.lock();
        try {
            if (s.connection != c || s.ended) {
                return;
            }
            s.ended = true;
            if (session == s) {
                session = null;
            }
        } finally {
            lock.unlock();
        }
        if (error != null) {
            LOGGER.log(Level.WARNING, "Chat session ended with an error: " + getBid(), error);
        }
        eventEmitter.emit(ChatEvent.DISCONNECTED, event != null ? event : errorEvent(error));
        if (error == null) {
            s.done.complete(null);
        } else {
            s.done.completeExceptionally(toConnectionException(error));
        }
    }

    /**
     * 채팅에 연결하고 세션이 끝날 때까지 현재 스레드를 블로킹합니다.
     *
     * @throws ConnectionException 연결에 실패한 경우
     */
    public void connectAndAwait() throws ConnectionException {
        try {
            connectToChat().join();
        } catch (CompletionException e) {
            if (e.getCause() instanceof ConnectionException ce) {
                throw ce;
            } else {
                throw new ConnectionException("Chat connection failed", e);
            }
        }
    }

    /**
     * 현재 세션이 채널에 들어가면(서버가 JOIN에 응답하면) 완료되는 future를 돌려줍니다.
     *
     * <ul>
     *   <li>지금 채널에 들어가 있으면 이미 완료된 future를 돌려줍니다.
     *   <li>연결 중이거나 재연결을 기다리는 중이면 세션이 다음에 채널에 들어갈 때 완료됩니다. {@link #forceReconnect()}로 연결이 바뀌어도 실패하지
     *       않고 새 연결을 기다립니다.
     *   <li>그 전에 세션이 끝나면({@link #disconnect()}, {@link #close()}, 서버의 연결 종료, 연결 실패, 재시도 소진) {@link
     *       ConnectionException}으로 예외 완료됩니다.
     * </ul>
     *
     * <p>future는 lane을 거치지 않고, 대개 JOIN 응답을 받은 수신 스레드에서 {@link ChatEvent#JOIN_CHANNEL} 리스너보다 먼저
     * 완료됩니다. 인증 연결의 ENTER_INFO는 그 전에 송신 순서에 들어가므로, 완료되자마자 보낸 메시지도 ENTER_INFO 뒤에 나갑니다. 리스너 안에서 기다려도
     * 교착 상태가 되지 않지만, 기다리는 동안 이 클라이언트의 이벤트 전달은 멈춥니다. 이어지는 작업이 오래 걸리면 {@code thenRunAsync}로 넘기세요.
     *
     * <p>입장을 기다리는 동안 받은 future는 다음 입장이나 세션 종료까지 연결에 남습니다. 제한 시간을 두고 주기적으로 다시 부르기보다 받은 future 하나를
     * 재사용하세요.
     *
     * <pre>{@code
     * chat.connectToChat();
     * chat.ready().thenRun(() -> chat.sendChat("hi"));
     * }</pre>
     *
     * @return 채널에 들어가면 완료되는 future. 진행 중인 세션이 없으면(연결 전, 세션이 끝난 뒤) {@link IllegalStateException}으로,
     *     {@link #close()} 뒤에는 {@code IllegalStateException("Client is closed")}로 실패한 future
     */
    public CompletableFuture<Void> ready() {
        Session s;
        ChatConnection c;
        lock.lock();
        try {
            if (closed) {
                return CompletableFuture.failedFuture(closedException());
            }
            s = session;
            if (s == null) {
                return CompletableFuture.failedFuture(notConnectedException());
            }
            c = s.connection;
        } finally {
            lock.unlock();
        }
        return awaitReady(s, c);
    }

    /**
     * 연결 {@code c}가 채널에 들어가길 기다린다. {@code c}가 끝났어도 세션이 {@link #forceReconnect()}로 다른 연결을 붙였으면 그 연결을
     * 이어서 기다린다. 세션 종료를 lane에서 처리하는 {@link #onTerminated}를 거치지 않으므로, lane에서 기다려도 막히지 않는다.
     */
    private CompletableFuture<Void> awaitReady(Session s, ChatConnection c) {
        return c.ready()
                .exceptionallyCompose(
                        error -> {
                            ChatConnection next = replacementFor(s, c);
                            return next != null
                                    ? awaitReady(s, next)
                                    : CompletableFuture.failedFuture(toConnectionException(error));
                        });
    }

    /**
     * {@code c}의 ready()가 실패했다(연결이 끝났다). 세션 {@code s}가 아직 현재 세션이고 {@code c} 대신 다른 연결이 붙어 있으면 그 연결을
     * 돌려준다. {@code c}가 아직 세션의 연결이면 세션은 이 연결과 함께 끝나므로, lane의 {@link #onTerminated}를 기다리지 않고 여기서 떼어 낸
     * 뒤 null을 돌려준다. 그래야 그 사이에 온 {@link #forceReconnect()}가 끝나는 세션을 이어 붙여 이미 실패한 ready()와 어긋나는 일 없이
     * 새 세션을 시작한다. DISCONNECTED는 {@link #disconnect()} 때처럼 lane에서 뒤따라 온다.
     */
    private ChatConnection replacementFor(Session s, ChatConnection c) {
        lock.lock();
        try {
            if (session != s) {
                return null;
            }
            if (s.connection != c) {
                return s.connection;
            }
            session = null;
            return null;
        } finally {
            lock.unlock();
        }
    }

    /**
     * 채팅 메시지를 전송합니다.
     *
     * @param message 전송할 메시지. 비어 있을 수 없고, 제어 문자 U+000C·U+001B를 포함할 수 없습니다.
     * @return 전송이 완료되면 완료되는 CompletableFuture. 메시지가 올바르지 않으면 {@link IllegalArgumentException}, 인증되지
     *     않았으면 {@link AuthenticationException}, 연결되지 않았으면 {@link IllegalStateException}으로 예외 완료됩니다.
     */
    public CompletableFuture<Void> sendChat(String message) {
        if (message == null || message.isBlank()) {
            return CompletableFuture.failedFuture(blankMessageException());
        }
        if (!config.isAuthenticated()) {
            return CompletableFuture.failedFuture(
                    new AuthenticationException(
                            "Authentication required. Set AuthCookie to send chat messages."));
        }
        ChatConnection c = currentConnection();
        if (c == null) {
            return CompletableFuture.failedFuture(notConnectedException());
        }
        return c.sendChat(message);
    }

    /**
     * 특정 사용자에게 귓말(다이렉트 채팅)을 전송합니다.
     *
     * @param targetId 받는 사람의 SOOP 로그인 ID (예: {@code "targetUser"}). 닉네임이나 런타임 {@code (n)} 접미사 형태가
     *     아닙니다.
     * @param message 전송할 메시지. 비어 있을 수 없고, 제어 문자 U+000C·U+001B를 포함할 수 없습니다.
     * @return 전송이 완료되면 완료되는 CompletableFuture. 인자가 올바르지 않으면 {@link IllegalArgumentException}, 인증되지
     *     않았으면 {@link AuthenticationException}, 연결되지 않았으면 {@link IllegalStateException}으로 예외 완료됩니다.
     */
    public CompletableFuture<Void> sendWhisper(String targetId, String message) {
        if (targetId == null || targetId.isBlank()) {
            return CompletableFuture.failedFuture(
                    new IllegalArgumentException("targetId must not be null or blank"));
        }
        if (message == null || message.isBlank()) {
            return CompletableFuture.failedFuture(blankMessageException());
        }
        if (!config.isAuthenticated()) {
            return CompletableFuture.failedFuture(
                    new AuthenticationException(
                            "Authentication required. Set AuthCookie to send whisper messages."));
        }
        ChatConnection c = currentConnection();
        if (c == null) {
            return CompletableFuture.failedFuture(notConnectedException());
        }
        return c.sendWhisper(targetId, message);
    }

    /**
     * 현재 연결 위에서 WebSocket만 다시 엽니다. 방송 정보를 다시 조회하지는 않습니다.
     *
     * <p>새 소켓이 수립되면 완료됩니다. 방송 정보부터 새로 받아야 하면 {@link #forceReconnect()}를 사용하세요.
     */
    public CompletableFuture<Void> reconnect() {
        ChatConnection c = currentConnection();
        if (c == null) {
            return CompletableFuture.failedFuture(notConnectedException());
        }
        return c.reconnect();
    }

    /**
     * 현재 연결을 버리고 방송 정보 조회부터 새로 연결합니다. 세션은 유지됩니다.
     *
     * <p>동작 순서:
     *
     * <ol>
     *   <li>새 연결을 세션에 붙인다. 이후 옛 연결의 종료는 무시된다.
     *   <li>{@link ChatEvent#RECONNECTING}을 emit한다({@code attemptNumber=1, maxAttempts=1,
     *       delayMs=0}).
     *   <li>옛 연결을 닫고 새 연결을 시작한다. 성공하면 {@link ChatEvent#RECONNECTED}를 emit한다.
     * </ol>
     *
     * <p>{@link ChatEvent#DISCONNECTED}는 발생하지 않고, 기다리던 {@link #ready()}도 실패하지 않은 채 새 연결이 채널에 들어갈 때
     * 완료됩니다. 진행 중인 세션이 없으면 새 세션을 시작합니다.
     *
     * @return 세션이 끝날 때 완료되는 future({@link #connectToChat()}과 같은 객체)
     */
    public CompletableFuture<Void> forceReconnect() {
        Session s;
        ChatConnection old;
        ChatConnection fresh;
        lock.lock();
        try {
            if (closed) {
                return CompletableFuture.failedFuture(closedException());
            }
            s = session;
            if (s != null) {
                try {
                    fresh = factory.createConnection(config, eventEmitter, lane);
                } catch (RuntimeException e) {
                    return CompletableFuture.failedFuture(
                            new ConnectionException("Failed to create connection", e));
                }
                // 같은 임계구역에서 연결을 바꿔 끼운다. 이후 옛 연결의 종료는 onTerminated에서 무시된다.
                old = s.connection;
                s.connection = fresh;
            } else {
                old = null;
                fresh = null;
            }
        } finally {
            lock.unlock();
        }
        if (s == null) {
            return connectToChat();
        }

        emitOnLane(
                ChatEvent.RECONNECTING,
                new ReconnectingEvent(
                        1, 1, 0L, ChatEvent.RECONNECTING, "", System.currentTimeMillis()));
        old.close();
        Session target = s;
        start(target, fresh)
                .whenCompleteAsync(
                        (unused, error) -> {
                            if (error == null && isCurrent(target, fresh)) {
                                eventEmitter.emit(
                                        ChatEvent.RECONNECTED,
                                        new ReconnectedEvent(
                                                1,
                                                ChatEvent.RECONNECTED,
                                                "",
                                                System.currentTimeMillis()));
                            }
                        },
                        lane);
        return s.publicDone;
    }

    public CompletableFuture<ConnectionStatus> getConnectionStatus() {
        ChatConnection c = currentConnection();
        if (c == null) {
            return CompletableFuture.completedFuture(new ConnectionStatus(false, false, 0));
        }
        return c.status();
    }

    /**
     * 세션을 끝냅니다. 연결 중이거나 재연결 대기 중이어도 끝나며, 블로킹하지 않습니다.
     *
     * <p>{@link ChatEvent#DISCONNECTED}({@link DisconnectedEvent#isClientInitiated()}가 true)는 이벤트
     * lane에서 비동기로 발생합니다. 이후 {@link #connectToChat()}으로 새 세션을 시작할 수 있습니다.
     */
    public void disconnect() {
        ChatConnection c;
        lock.lock();
        try {
            Session s = session;
            if (s == null) {
                return;
            }
            session = null;
            c = s.connection;
        } finally {
            lock.unlock();
        }
        c.close();
    }

    /** 세션을 끝내고 클라이언트를 닫습니다. 이후 {@link #connectToChat()}과 {@link #forceReconnect()}는 실패합니다. */
    @Override
    public void close() {
        lock.lock();
        try {
            closed = true;
        } finally {
            lock.unlock();
        }
        disconnect();
    }

    public boolean isConnected() {
        ChatConnection c = currentConnection();
        return c != null && c.isConnected();
    }

    public String getBid() {
        return config.getBid();
    }

    public EventEmitter getEventEmitter() {
        return eventEmitter;
    }

    private ChatConnection currentConnection() {
        lock.lock();
        try {
            return session != null ? session.connection : null;
        } finally {
            lock.unlock();
        }
    }

    private boolean isCurrent(Session s, ChatConnection c) {
        lock.lock();
        try {
            return s.connection == c && !s.ended;
        } finally {
            lock.unlock();
        }
    }

    private void emitOnLane(ChatEvent type, BaseEvent event) {
        lane.execute(() -> eventEmitter.emit(type, event));
    }

    private static DisconnectedEvent errorEvent(Throwable error) {
        Throwable cause = SOOPChatUtils.unwrapCompletionException(error);
        String message = cause.getMessage() != null ? cause.getMessage() : cause.toString();
        return new DisconnectedEvent(
                -1, message, true, ChatEvent.DISCONNECTED, "", System.currentTimeMillis());
    }

    private static ConnectionException toConnectionException(Throwable error) {
        Throwable cause = SOOPChatUtils.unwrapCompletionException(error);
        return cause instanceof ConnectionException ce
                ? ce
                : new ConnectionException("Chat connection failed", cause);
    }

    private static IllegalArgumentException blankMessageException() {
        return new IllegalArgumentException("message must not be null or blank");
    }

    private static IllegalStateException notConnectedException() {
        return new IllegalStateException("Not connected. Call connectToChat() first.");
    }

    private static IllegalStateException closedException() {
        return new IllegalStateException("Client is closed");
    }
}
