package com.github.getcurrentthread.soopapi.client;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.util.concurrent.CompletionException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import com.github.getcurrentthread.soopapi.api.SOOPHttpClient;
import com.github.getcurrentthread.soopapi.api.SOOPLive;
import com.github.getcurrentthread.soopapi.api.model.AuthCookie;
import com.github.getcurrentthread.soopapi.api.model.LiveDetail;
import com.github.getcurrentthread.soopapi.config.SOOPChatConfig;
import com.github.getcurrentthread.soopapi.event.ChatEvent;
import com.github.getcurrentthread.soopapi.event.model.ChatMessageEvent;
import com.github.getcurrentthread.soopapi.exception.AuthenticationException;
import com.github.getcurrentthread.soopapi.model.ChannelInfo;

public class SOOPChatClientTest {

    private static final Logger LOGGER = Logger.getLogger(SOOPChatClientTest.class.getName());

    @Test
    @Tag("integration")
    public void testSOOPChatClientConnection() throws Exception {
        String testBID = "lshooooo";
        LOGGER.info("Starting test with BID: " + testBID);

        SOOPLive soopLive = new SOOPLive(new SOOPHttpClient());
        String bno;
        try {
            bno = soopLive.getBno(testBID).join();
        } catch (CompletionException e) {
            assumeTrue(false, "Streamer is not live: " + e.getCause());
            return;
        }
        LOGGER.info("Retrieved BNO: " + bno);

        LiveDetail liveDetail = soopLive.detail(testBID, bno).join();
        ChannelInfo channelInfo = soopLive.toChannelInfo(liveDetail);
        LOGGER.info("Retrieved channel info: " + channelInfo);

        SOOPChatConfig config = new SOOPChatConfig.Builder().bid(testBID).bno(bno).build();
        SOOPChatClient client = new SOOPChatClient(config);
        // connectToChat()의 future는 세션이 끝날 때 완료되므로, 연결 성공은 JOIN_CHANNEL 수신으로 판정한다.
        CountDownLatch joinLatch = new CountDownLatch(1);
        CountDownLatch messageLatch = new CountDownLatch(10);

        client.once(ChatEvent.JOIN_CHANNEL, event -> joinLatch.countDown());
        client.on(
                ChatEvent.CHAT_MESSAGE,
                (ChatMessageEvent e) -> {
                    LOGGER.fine(e.senderNickname() + ": " + e.message());
                    messageLatch.countDown();
                });
        client.on(ChatEvent.QUIT_CHANNEL, event -> messageLatch.countDown());
        client.on(ChatEvent.CHAT_USER, event -> messageLatch.countDown());

        try {
            client.connectToChat()
                    .exceptionally(
                            throwable -> {
                                LOGGER.log(Level.SEVERE, "Connection error", throwable);
                                return null;
                            });

            assertTrue(
                    joinLatch.await(30, TimeUnit.SECONDS),
                    "JOIN_CHANNEL should arrive within 30 seconds");
            assertTrue(client.isConnected(), "Client should report connected after JOIN_CHANNEL");

            if (!messageLatch.await(60, TimeUnit.SECONDS)) {
                LOGGER.warning("Timed out waiting for messages (quiet stream?)");
            }
        } finally {
            client.disconnect();
        }
    }

    @Test
    void sendChat_withoutAuth_throwsAuthenticationException() {
        SOOPChatConfig config =
                new SOOPChatConfig.Builder().bid("testStreamer").bno("12345").build();

        SOOPChatClient client = new SOOPChatClient(config);

        ExecutionException ex =
                assertThrows(ExecutionException.class, () -> client.sendChat("Hello!").get());

        assertInstanceOf(AuthenticationException.class, ex.getCause());
    }

    @Test
    void sendChat_withFailedAuthCookie_throwsAuthenticationException() {
        AuthCookie failedCookie =
                new AuthCookie(
                        "user", false, "", null, null, null, null, null, null, null, null, null,
                        null);

        SOOPChatConfig config =
                new SOOPChatConfig.Builder()
                        .bid("testStreamer")
                        .bno("12345")
                        .authCookie(failedCookie)
                        .build();

        SOOPChatClient client = new SOOPChatClient(config);

        ExecutionException ex =
                assertThrows(ExecutionException.class, () -> client.sendChat("Hello!").get());

        assertInstanceOf(AuthenticationException.class, ex.getCause());
    }

    @Test
    void sendChat_withoutAuth_prioritizesAuthOverConnection() {
        SOOPChatConfig config =
                new SOOPChatConfig.Builder().bid("testStreamer").bno("12345").build();

        SOOPChatClient client = new SOOPChatClient(config);

        // 미연결 + 미인증 상태에서 인증 오류가 먼저 발생해야 함
        assertFalse(client.isConnected());

        ExecutionException ex =
                assertThrows(ExecutionException.class, () -> client.sendChat("Hello!").get());

        assertInstanceOf(
                AuthenticationException.class,
                ex.getCause(),
                "Authentication error should occur before connection error");
    }

    @Test
    void sendWhisper_withoutAuth_throwsAuthenticationException() {
        SOOPChatConfig config =
                new SOOPChatConfig.Builder().bid("testStreamer").bno("12345").build();

        SOOPChatClient client = new SOOPChatClient(config);

        ExecutionException ex =
                assertThrows(
                        ExecutionException.class,
                        () -> client.sendWhisper("targetUser", "Hello!").get());

        assertInstanceOf(AuthenticationException.class, ex.getCause());
    }

    @Test
    void sendWhisper_withBlankTargetId_throwsIllegalArgument() {
        SOOPChatConfig config =
                new SOOPChatConfig.Builder().bid("testStreamer").bno("12345").build();

        SOOPChatClient client = new SOOPChatClient(config);

        // targetId 검증은 인증/연결 검사보다 먼저 수행되므로 미인증 상태에서도 IllegalArgumentException이 발생해야 함
        ExecutionException ex =
                assertThrows(
                        ExecutionException.class, () -> client.sendWhisper("  ", "Hello!").get());

        assertInstanceOf(IllegalArgumentException.class, ex.getCause());
    }

    @Test
    void sendChat_blankMessage_failsBeforeAuthCheck() {
        SOOPChatConfig config =
                new SOOPChatConfig.Builder().bid("testStreamer").bno("12345").build();
        SOOPChatClient client = new SOOPChatClient(config);

        for (String message : new String[] {null, "", "   "}) {
            ExecutionException ex =
                    assertThrows(ExecutionException.class, () -> client.sendChat(message).get());
            assertInstanceOf(IllegalArgumentException.class, ex.getCause());
        }
    }

    @Test
    void sendWhisper_blankMessage_failsBeforeAuthCheck() {
        SOOPChatConfig config =
                new SOOPChatConfig.Builder().bid("testStreamer").bno("12345").build();
        SOOPChatClient client = new SOOPChatClient(config);

        ExecutionException ex =
                assertThrows(
                        ExecutionException.class,
                        () -> client.sendWhisper("targetUser", " ").get());

        assertInstanceOf(IllegalArgumentException.class, ex.getCause());
    }

    @Test
    void constructor_withoutBno_doesNotThrow() {
        SOOPChatConfig config = new SOOPChatConfig.Builder().bid("testStreamer").build();

        assertDoesNotThrow(() -> new SOOPChatClient(config));
    }

    @Test
    void constructor_withNullConfig_throwsIllegalArgument() {
        assertThrows(IllegalArgumentException.class, () -> new SOOPChatClient(null));
    }

    @Test
    void constructor_withNullBid_throwsIllegalArgument() {
        assertThrows(IllegalArgumentException.class, () -> new SOOPChatConfig.Builder().build());
    }
}
