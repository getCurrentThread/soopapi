package com.github.getcurrentthread.soopapi.client;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

import com.github.getcurrentthread.soopapi.config.SOOPChatConfig;
import com.github.getcurrentthread.soopapi.connection.FakeConnectionFactory;

class SOOPChatClientConcurrencyTest {

    @Test
    void concurrentConnectToChat_returnsSameFuture() throws Exception {
        SOOPChatConfig config =
                new SOOPChatConfig.Builder().bid("testConcurrency").bno("99999").build();
        FakeConnectionFactory factory = FakeConnectionFactory.async();

        SOOPChatClient client = new SOOPChatClient(config, factory);

        int threads = 10;
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(threads);
        Set<CompletableFuture<Void>> futures = ConcurrentHashMap.newKeySet();

        for (int i = 0; i < threads; i++) {
            Thread.startVirtualThread(
                    () -> {
                        try {
                            startLatch.await();
                            CompletableFuture<Void> future = client.connectToChat();
                            futures.add(future);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        } finally {
                            doneLatch.countDown();
                        }
                    });
        }

        startLatch.countDown();
        assertTrue(doneLatch.await(10, TimeUnit.SECONDS), "All threads should complete");

        // 연결을 수립시키지 않으므로 세션이 계속 진행 중이다. 모든 호출이 같은 세션을 봐야 한다.
        assertEquals(
                1,
                futures.size(),
                "Concurrent connectToChat() should return the same session future, got "
                        + futures.size()
                        + " distinct futures");
        assertEquals(1, factory.created.size(), "Only one connection should be created");

        client.disconnect();
        assertEquals(0, factory.live());
    }
}
