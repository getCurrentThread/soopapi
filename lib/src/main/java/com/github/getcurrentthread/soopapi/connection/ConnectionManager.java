package com.github.getcurrentthread.soopapi.connection;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledThreadPoolExecutor;

import com.github.getcurrentthread.soopapi.config.SOOPChatConfig;
import com.github.getcurrentthread.soopapi.event.EventEmitter;
import com.github.getcurrentthread.soopapi.util.SerialExecutor;

/**
 * 모든 채팅 연결이 공유하는 실행 자원과 기본 {@link ChatConnectionFactory}.
 *
 * <p>연결 자체는 소유하지 않습니다. 연결은 {@link com.github.getcurrentthread.soopapi.client.SOOPChatClient}가 세션마다
 * 만들고 닫습니다. 스레드는 모두 데몬(가상 스레드 포함)이라 JVM 종료를 막지 않으므로 별도의 종료 절차가 없습니다.
 */
public final class ConnectionManager implements ChatConnectionFactory {

    private static final class Holder {
        static final ConnectionManager INSTANCE = new ConnectionManager();
    }

    private final ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor();
    private final ScheduledThreadPoolExecutor scheduler;

    private ConnectionManager() {
        scheduler =
                new ScheduledThreadPoolExecutor(
                        1,
                        r -> {
                            Thread t = new Thread(r, "SOOP-Scheduler");
                            t.setDaemon(true);
                            return t;
                        });
        scheduler.setRemoveOnCancelPolicy(true);
    }

    public static ConnectionManager getInstance() {
        return Holder.INSTANCE;
    }

    @Override
    public ChatConnection createConnection(
            SOOPChatConfig config, EventEmitter emitter, SerialExecutor lane) {
        return new SOOPConnection(config, scheduler, emitter, lane);
    }

    @Override
    public SerialExecutor newLane() {
        return new SerialExecutor(pool);
    }
}
