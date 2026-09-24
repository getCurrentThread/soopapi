package com.github.getcurrentthread.soopapi.util;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

class SerialExecutorTest {

    @Test
    void tasksRunInSubmissionOrderWithoutOverlap() throws Exception {
        int n = 1000;
        List<Integer> order = new CopyOnWriteArrayList<>();
        AtomicInteger inFlight = new AtomicInteger();
        AtomicInteger overlaps = new AtomicInteger();
        CountDownLatch done = new CountDownLatch(n);

        try (ExecutorService pool = Executors.newFixedThreadPool(8)) {
            SerialExecutor lane = new SerialExecutor(pool);
            for (int i = 0; i < n; i++) {
                int seq = i;
                lane.execute(
                        () -> {
                            if (inFlight.incrementAndGet() > 1) {
                                overlaps.incrementAndGet();
                            }
                            order.add(seq);
                            inFlight.decrementAndGet();
                            done.countDown();
                        });
            }
            assertTrue(done.await(10, TimeUnit.SECONDS));
        }

        assertEquals(0, overlaps.get(), "Lane tasks must never overlap");
        for (int i = 0; i < n; i++) {
            assertEquals(i, order.get(i), "Lane tasks must run in submission order");
        }
    }

    @Test
    void concurrentProducers_neverOverlapAndLoseNothing() throws Exception {
        int producers = 8;
        int perProducer = 500;
        AtomicInteger inFlight = new AtomicInteger();
        AtomicInteger overlaps = new AtomicInteger();
        AtomicInteger executed = new AtomicInteger();
        CountDownLatch done = new CountDownLatch(producers * perProducer);
        CountDownLatch start = new CountDownLatch(1);

        try (ExecutorService pool = Executors.newFixedThreadPool(8);
                ExecutorService producerPool = Executors.newFixedThreadPool(producers)) {
            SerialExecutor lane = new SerialExecutor(pool);
            for (int p = 0; p < producers; p++) {
                producerPool.execute(
                        () -> {
                            try {
                                start.await();
                            } catch (InterruptedException e) {
                                Thread.currentThread().interrupt();
                                return;
                            }
                            for (int i = 0; i < perProducer; i++) {
                                lane.execute(
                                        () -> {
                                            if (inFlight.incrementAndGet() > 1) {
                                                overlaps.incrementAndGet();
                                            }
                                            executed.incrementAndGet();
                                            inFlight.decrementAndGet();
                                            done.countDown();
                                        });
                            }
                        });
            }
            start.countDown();
            assertTrue(done.await(10, TimeUnit.SECONDS));
        }

        assertEquals(0, overlaps.get());
        assertEquals(producers * perProducer, executed.get());
    }

    @Test
    void rejectingPool_runsTasksInlineInsteadOfDropping() {
        SerialExecutor lane =
                new SerialExecutor(
                        r -> {
                            throw new RejectedExecutionException("closed");
                        });
        List<Integer> order = new CopyOnWriteArrayList<>();

        for (int i = 0; i < 600; i++) {
            int seq = i;
            lane.execute(() -> order.add(seq));
        }

        assertEquals(600, order.size(), "No task may be dropped when the pool rejects");
        for (int i = 0; i < 600; i++) {
            assertEquals(i, order.get(i));
        }
        assertEquals(0, lane.pending());
    }

    @Test
    void failingTask_doesNotStopLaterTasks() {
        SerialExecutor lane = new SerialExecutor(Runnable::run);
        AtomicInteger ran = new AtomicInteger();

        lane.execute(
                () -> {
                    throw new IllegalStateException("boom");
                });
        lane.execute(ran::incrementAndGet);

        assertEquals(1, ran.get());
    }

    @Test
    void whenPendingAtMost_firesOnceWhenQueueDrains() throws Exception {
        CountDownLatch gate = new CountDownLatch(1);
        AtomicInteger fired = new AtomicInteger();
        CountDownLatch firedLatch = new CountDownLatch(1);

        try (ExecutorService pool = Executors.newSingleThreadExecutor()) {
            SerialExecutor lane = new SerialExecutor(pool);
            lane.execute(
                    () -> {
                        try {
                            gate.await();
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                    });
            for (int i = 0; i < 9; i++) {
                lane.execute(() -> {});
            }
            assertEquals(10, lane.pending());

            lane.whenPendingAtMost(
                    2,
                    () -> {
                        fired.incrementAndGet();
                        firedLatch.countDown();
                    });
            assertEquals(0, fired.get(), "Callback must wait until the backlog shrinks");

            gate.countDown();
            assertTrue(firedLatch.await(5, TimeUnit.SECONDS));
        }
        assertEquals(1, fired.get(), "Callback is one-shot");
    }

    @Test
    void whenPendingAtMost_firesImmediatelyWhenAlreadyBelow() {
        SerialExecutor lane = new SerialExecutor(Runnable::run);
        AtomicInteger fired = new AtomicInteger();

        lane.whenPendingAtMost(0, fired::incrementAndGet);

        assertEquals(1, fired.get());
    }
}
