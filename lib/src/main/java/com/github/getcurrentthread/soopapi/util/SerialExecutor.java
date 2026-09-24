package com.github.getcurrentthread.soopapi.util;

import java.util.Objects;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * 공유 풀 위에서 태스크를 제출 순서대로 하나씩 실행하는 직렬 실행기(lane).
 *
 * <p>한 lane에 제출된 태스크는 FIFO로 실행되며 서로 겹치지 않습니다. 여러 lane이 같은 풀을 공유해도 lane끼리는 병렬로 진행됩니다.
 *
 * <ul>
 *   <li>태스크 예외는 로그로 격리되고 다음 태스크 실행에 영향을 주지 않습니다.
 *   <li>풀이 태스크를 거부하면(종료 등) 태스크를 버리지 않고 제출한 스레드에서 직접 실행합니다.
 *   <li>{@link #pending()}과 {@link #whenPendingAtMost(int, Runnable)}로 대기열 길이에 따른 백프레셔를 걸 수 있습니다.
 * </ul>
 */
public final class SerialExecutor implements Executor {
    private static final Logger LOGGER = Logger.getLogger(SerialExecutor.class.getName());

    /** 한 번의 drain에서 연속 실행할 최대 태스크 수. 초과하면 풀에 다시 제출해 다른 lane에 양보합니다. */
    private static final int BATCH = 256;

    private final Executor pool;
    private final Queue<Runnable> queue = new ConcurrentLinkedQueue<>();
    private final AtomicInteger pending = new AtomicInteger();
    private final AtomicBoolean draining = new AtomicBoolean();
    private final AtomicReference<Waiter> waiter = new AtomicReference<>();

    private record Waiter(int threshold, Runnable callback) {}

    public SerialExecutor(Executor pool) {
        this.pool = Objects.requireNonNull(pool, "pool");
    }

    @Override
    public void execute(Runnable task) {
        Objects.requireNonNull(task, "task");
        pending.incrementAndGet();
        queue.add(task);
        if (draining.compareAndSet(false, true)) {
            submitDrain();
        }
    }

    /** 제출됐지만 아직 실행이 끝나지 않은 태스크 수. */
    public int pending() {
        return pending.get();
    }

    /**
     * 대기 태스크 수가 {@code threshold} 이하가 되면 {@code callback}을 한 번 실행합니다. 이미 이하라면 즉시 호출 스레드에서 실행합니다.
     * 등록은 하나만 유지되며, 새로 등록하면 이전 등록을 대체합니다.
     */
    public void whenPendingAtMost(int threshold, Runnable callback) {
        Objects.requireNonNull(callback, "callback");
        waiter.set(new Waiter(threshold, callback));
        fireWaiterIfReady();
    }

    private void submitDrain() {
        try {
            pool.execute(() -> drain(true));
        } catch (RejectedExecutionException e) {
            // 풀이 닫혀도 태스크를 버리지 않는다. draining 플래그는 이미 이 호출이 쥐고 있다.
            LOGGER.log(Level.FINE, "Pool rejected lane drain; running inline", e);
            drain(false);
        }
    }

    private void drain(boolean mayYield) {
        int ran = 0;
        do {
            Runnable task;
            while ((task = queue.poll()) != null) {
                try {
                    task.run();
                } catch (Throwable t) {
                    LOGGER.log(Level.WARNING, "Lane task failed", t);
                }
                pending.decrementAndGet();
                fireWaiterIfReady();
                if (mayYield && ++ran >= BATCH && !queue.isEmpty()) {
                    submitDrain();
                    return;
                }
            }
            draining.set(false);
            // 플래그를 내리기 직전에 들어온 태스크를 놓치지 않도록 다시 확인한다.
        } while (!queue.isEmpty() && draining.compareAndSet(false, true));
    }

    private void fireWaiterIfReady() {
        Waiter w = waiter.get();
        if (w != null && pending.get() <= w.threshold() && waiter.compareAndSet(w, null)) {
            try {
                w.callback().run();
            } catch (Throwable t) {
                LOGGER.log(Level.WARNING, "Lane pending callback failed", t);
            }
        }
    }
}
