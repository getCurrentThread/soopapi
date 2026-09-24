package com.github.getcurrentthread.soopapi;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.locks.ReentrantLock;
import java.util.logging.Level;
import java.util.logging.Logger;

import com.github.getcurrentthread.soopapi.api.SOOPAuth;
import com.github.getcurrentthread.soopapi.api.SOOPChannel;
import com.github.getcurrentthread.soopapi.api.SOOPHttpClient;
import com.github.getcurrentthread.soopapi.api.SOOPLive;
import com.github.getcurrentthread.soopapi.client.SOOPChatClient;
import com.github.getcurrentthread.soopapi.config.SOOPChatConfig;
import com.github.getcurrentthread.soopapi.config.SOOPClientConfig;
import com.github.getcurrentthread.soopapi.connection.ChatConnectionFactory;
import com.github.getcurrentthread.soopapi.connection.ConnectionManager;
import com.github.getcurrentthread.soopapi.event.ChatEvent;
import com.github.getcurrentthread.soopapi.event.EventListener;
import com.github.getcurrentthread.soopapi.event.StreamEventListener;
import com.github.getcurrentthread.soopapi.event.model.BaseEvent;

public class SOOPClient implements AutoCloseable {
    private static final Logger LOGGER = Logger.getLogger(SOOPClient.class.getName());

    private final SOOPAuth auth;
    private final SOOPLive live;
    private final SOOPChannel channel;
    private final SOOPHttpClient httpClient;
    private final int defaultMaxRetryAttempts;
    private final ChatConnectionFactory connectionFactory;

    private final Map<String, SOOPChatClient> chatClients = new ConcurrentHashMap<>();
    private final Map<ChatEvent, List<GlobalSub<?>>> globalSubs = new ConcurrentHashMap<>();
    private final ReentrantLock lock = new ReentrantLock();

    public SOOPClient() {
        this(new SOOPClientConfig.Builder().build());
    }

    /**
     * @param config {@code connectionTimeout}은 REST API({@link #auth()}, {@link #live()}, {@link
     *     #channel()})에, {@code maxRetryAttempts}는 {@link #add(String)}로 등록하는 스트림의 재연결 한도에 쓰입니다.
     */
    public SOOPClient(SOOPClientConfig config) {
        this(config, ConnectionManager.getInstance());
    }

    SOOPClient(SOOPClientConfig config, ChatConnectionFactory connectionFactory) {
        this.connectionFactory = connectionFactory;
        this.defaultMaxRetryAttempts = config.getMaxRetryAttempts();
        this.httpClient = new SOOPHttpClient(config.getConnectionTimeout());
        this.auth = new SOOPAuth(httpClient);
        this.live = new SOOPLive(httpClient);
        this.channel = new SOOPChannel(httpClient);
    }

    public SOOPAuth auth() {
        return auth;
    }

    public SOOPLive live() {
        return live;
    }

    public SOOPChannel channel() {
        return channel;
    }

    /**
     * 기본 설정으로 채팅 클라이언트를 등록하고 즉시 비동기 연결을 시작합니다.
     *
     * <p>동일 {@code streamerId}로 이미 등록된 클라이언트가 있으면 기존 인스턴스를 반환합니다(중복 등록 방지). 이전에 {@code
     * disconnect()} 되었던 인스턴스라면 새 세션으로 다시 연결됩니다. 재연결 한도는 {@link
     * SOOPClientConfig#getMaxRetryAttempts()}를 따릅니다.
     */
    public SOOPChatClient add(String streamerId) {
        return add(
                new SOOPChatConfig.Builder()
                        .bid(streamerId)
                        .maxRetryAttempts(defaultMaxRetryAttempts)
                        .build());
    }

    /**
     * 주어진 설정으로 채팅 클라이언트를 등록하고 즉시 비동기 연결을 시작합니다.
     *
     * <p>{@code config.getBid()} 기준으로 dedup됩니다. 이미 등록된 bid면 <b>기존</b> 인스턴스를 반환하며 전달된 config는 무시됩니다.
     * 반환된 인스턴스의 {@code connectToChat()}이 자동 호출되므로 호출자는 별도로 연결을 시작할 필요가 없습니다.
     *
     * <p>연결 실패는 {@code DISCONNECTED} 이벤트({@code causedByError=true})로 알려집니다. 세션 future가 필요하면 반환된
     * 클라이언트에서 {@code connectToChat()}을 호출하세요. 진행 중인 세션이 있으면 같은 future를 받습니다.
     */
    public SOOPChatClient add(SOOPChatConfig config) {
        if (config == null) {
            throw new IllegalArgumentException("config must not be null");
        }
        String bid = config.getBid();
        if (bid == null || bid.isBlank()) {
            throw new IllegalArgumentException("bid must not be null or blank");
        }
        lock.lock();
        try {
            SOOPChatClient client = chatClients.get(bid);
            if (client == null) {
                client = new SOOPChatClient(config, connectionFactory);
                attachGlobalSubs(bid, client);
                chatClients.put(bid, client);
            }
            // connectToChat()은 블로킹하지 않고 콜백도 lane에서 돌므로 lock 안에서 불러도 된다.
            // lock 밖에서 부르면 동시에 remove()된 클라이언트에 연결을 다시 시작할 수 있다.
            client.connectToChat();
            return client;
        } finally {
            lock.unlock();
        }
    }

    /**
     * 등록된 채팅 클라이언트를 해제하고 연결을 닫습니다.
     *
     * @return 등록되어 있어 제거가 일어났으면 {@code true}, 없었으면 {@code false}.
     */
    public boolean remove(String streamerId) {
        SOOPChatClient client;
        lock.lock();
        try {
            client = chatClients.remove(streamerId);
            if (client == null) {
                return false;
            }
            detachGlobalSubs(streamerId, client);
        } finally {
            lock.unlock();
        }
        try {
            client.close();
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "Error closing chat client during remove", e);
        }
        return true;
    }

    public SOOPChatClient get(String streamerId) {
        return chatClients.get(streamerId);
    }

    public Set<String> streamerIds() {
        return Set.copyOf(chatClients.keySet());
    }

    public Collection<SOOPChatClient> clients() {
        return Collections.unmodifiableCollection(new LinkedHashMap<>(chatClients).values());
    }

    /**
     * 등록된 스트림에 대해 tear-down + 새 연결로 강제 재연결합니다.
     *
     * <p>현재 연결 상태(연결 안 됨/연결 중/backoff 중)와 무관하게 즉시 처음부터 다시 시도합니다. 자세한 동작은 {@link
     * SOOPChatClient#forceReconnect()} 참고.
     *
     * @return 세션이 끝날 때 완료되는 future. 등록되지 않은 bid면 {@link IllegalArgumentException}으로 실패.
     */
    public CompletableFuture<Void> reconnect(String streamerId) {
        lock.lock();
        try {
            SOOPChatClient client = chatClients.get(streamerId);
            if (client == null) {
                return CompletableFuture.failedFuture(
                        new IllegalArgumentException("Unknown streamerId: " + streamerId));
            }
            return client.forceReconnect();
        } finally {
            lock.unlock();
        }
    }

    /** 등록된 모든 스트림을 강제 재연결하고, 모든 세션이 끝날 때 완료되는 future를 반환합니다. */
    public CompletableFuture<Void> reconnectAll() {
        lock.lock();
        try {
            return CompletableFuture.allOf(
                    chatClients.values().stream()
                            .map(SOOPChatClient::forceReconnect)
                            .toArray(CompletableFuture[]::new));
        } finally {
            lock.unlock();
        }
    }

    /**
     * 등록된 모든 클라이언트의 세션이 끝날 때 완료되는 future를 반환합니다.
     *
     * <p>{@link #add(String)}이 이미 연결을 시작하므로 이 메서드는 실질적으로 <i>"모든 세션이 끝날 때까지 대기"</i> 역할을 합니다. 끝난 세션이
     * 있으면 새로 시작합니다. 어느 한 세션이라도 연결 실패나 재시도 소진으로 끝나면 예외로 완료됩니다.
     */
    public CompletableFuture<Void> connectAll() {
        lock.lock();
        try {
            return CompletableFuture.allOf(
                    chatClients.values().stream()
                            .map(SOOPChatClient::connectToChat)
                            .toArray(CompletableFuture[]::new));
        } finally {
            lock.unlock();
        }
    }

    /** 등록된 <b>모든</b> 스트림과 이후 추가되는 스트림에 streamerId를 함께 전달하는 글로벌 리스너를 등록합니다. */
    public <T extends BaseEvent> SOOPClient on(ChatEvent event, StreamEventListener<T> listener) {
        lock.lock();
        try {
            GlobalSub<T> sub = new GlobalSub<>(event, listener);
            for (Map.Entry<String, SOOPChatClient> entry : chatClients.entrySet()) {
                String bid = entry.getKey();
                SOOPChatClient client = entry.getValue();
                EventListener<T> wrapper = e -> listener.onEvent(bid, e);
                client.on(event, wrapper);
                sub.wrappersByBid.put(bid, wrapper);
            }
            globalSubs.computeIfAbsent(event, _ -> new CopyOnWriteArrayList<>()).add(sub);
        } finally {
            lock.unlock();
        }
        return this;
    }

    /** {@link #on(ChatEvent, StreamEventListener)}으로 등록한 글로벌 리스너를 모든 스트림에서 제거합니다. */
    public <T extends BaseEvent> SOOPClient off(ChatEvent event, StreamEventListener<T> listener) {
        lock.lock();
        try {
            List<GlobalSub<?>> list = globalSubs.get(event);
            if (list == null) {
                return this;
            }
            GlobalSub<?> matched = null;
            for (GlobalSub<?> s : list) {
                if (s.listener == listener) {
                    matched = s;
                    break;
                }
            }
            if (matched == null) {
                return this;
            }
            list.remove(matched);
            detachWrappers(event, matched);
        } finally {
            lock.unlock();
        }
        return this;
    }

    /**
     * 단일 스트리머 편의 메서드. {@link #add(String)}에 위임하며 dedup이 적용됩니다 — 동일 bid로 두 번 호출하면 같은 인스턴스를 반환합니다.
     */
    public SOOPChatClient chat(String streamerId) {
        return add(streamerId);
    }

    /** 단일 스트리머 편의 메서드. {@link #add(SOOPChatConfig)}에 위임하며 dedup이 적용됩니다. */
    public SOOPChatClient chat(SOOPChatConfig config) {
        return add(config);
    }

    /** 등록된 모든 채팅 클라이언트를 닫고 등록을 비웁니다. 닫힌 클라이언트는 다시 연결되지 않습니다. */
    @Override
    public void close() {
        List<SOOPChatClient> snapshot;
        lock.lock();
        try {
            snapshot = List.copyOf(chatClients.values());
            for (Map.Entry<String, SOOPChatClient> entry : chatClients.entrySet()) {
                detachGlobalSubs(entry.getKey(), entry.getValue());
            }
            chatClients.clear();
            globalSubs.clear();
        } finally {
            lock.unlock();
        }
        for (SOOPChatClient client : snapshot) {
            try {
                client.close();
            } catch (Exception e) {
                LOGGER.log(Level.WARNING, "Error closing chat client", e);
            }
        }
        httpClient.close();
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private void attachGlobalSubs(String bid, SOOPChatClient client) {
        for (Map.Entry<ChatEvent, List<GlobalSub<?>>> entry : globalSubs.entrySet()) {
            ChatEvent event = entry.getKey();
            for (GlobalSub<?> sub : entry.getValue()) {
                EventListener wrapper = e -> ((StreamEventListener) sub.listener).onEvent(bid, e);
                client.on(event, wrapper);
                sub.wrappersByBid.put(bid, wrapper);
            }
        }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private void detachGlobalSubs(String bid, SOOPChatClient client) {
        for (Map.Entry<ChatEvent, List<GlobalSub<?>>> entry : globalSubs.entrySet()) {
            ChatEvent event = entry.getKey();
            for (GlobalSub<?> sub : entry.getValue()) {
                EventListener<?> wrapper = sub.wrappersByBid.remove(bid);
                if (wrapper != null) {
                    client.off(event, (EventListener) wrapper);
                }
            }
        }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private void detachWrappers(ChatEvent event, GlobalSub<?> sub) {
        for (Map.Entry<String, EventListener<?>> entry : sub.wrappersByBid.entrySet()) {
            SOOPChatClient client = chatClients.get(entry.getKey());
            if (client != null) {
                client.off(event, (EventListener) entry.getValue());
            }
        }
        sub.wrappersByBid.clear();
    }

    private static final class GlobalSub<T extends BaseEvent> {
        final ChatEvent event;
        final StreamEventListener<T> listener;
        final Map<String, EventListener<?>> wrappersByBid = new ConcurrentHashMap<>();

        GlobalSub(ChatEvent event, StreamEventListener<T> listener) {
            this.event = event;
            this.listener = listener;
        }
    }
}
