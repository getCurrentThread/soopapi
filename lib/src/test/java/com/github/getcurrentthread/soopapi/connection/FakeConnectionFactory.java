package com.github.getcurrentthread.soopapi.connection;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

import com.github.getcurrentthread.soopapi.config.SOOPChatConfig;
import com.github.getcurrentthread.soopapi.event.EventEmitter;
import com.github.getcurrentthread.soopapi.util.SerialExecutor;

/** {@link FakeChatConnection}을 만드는 팩토리. 만든 연결을 모두 기억합니다. */
public final class FakeConnectionFactory implements ChatConnectionFactory {
    public final List<FakeChatConnection> created = new CopyOnWriteArrayList<>();
    private final Executor pool;
    private final boolean autoEstablish;

    private FakeConnectionFactory(Executor pool, boolean autoEstablish) {
        this.pool = pool;
        this.autoEstablish = autoEstablish;
    }

    /** 실제처럼 가상 스레드 풀 위의 lane을 쓰고, 연결은 테스트가 직접 수립시킵니다. */
    public static FakeConnectionFactory async() {
        return new FakeConnectionFactory(Executors.newVirtualThreadPerTaskExecutor(), false);
    }

    /** lane이 호출 스레드에서 바로 실행되고, 연결이 만들어지자마자 수립됩니다. */
    public static FakeConnectionFactory synchronous() {
        return new FakeConnectionFactory(Runnable::run, true);
    }

    @Override
    public ChatConnection createConnection(
            SOOPChatConfig config, EventEmitter emitter, SerialExecutor lane) {
        FakeChatConnection connection = new FakeChatConnection(config, emitter, lane);
        created.add(connection);
        if (autoEstablish) {
            connection.establish();
        }
        return connection;
    }

    @Override
    public SerialExecutor newLane() {
        return new SerialExecutor(pool);
    }

    public FakeChatConnection last() {
        return created.getLast();
    }

    /** 닫히지 않은 연결 수. */
    public long live() {
        return created.stream().filter(c -> !c.isClosed()).count();
    }
}
