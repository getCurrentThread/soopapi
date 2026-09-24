package com.github.getcurrentthread.soopapi.client;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.io.PrintWriter;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.FileHandler;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import java.util.logging.SimpleFormatter;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.function.Executable;

import com.github.getcurrentthread.soopapi.api.SOOPAuth;
import com.github.getcurrentthread.soopapi.api.SOOPHttpClient;
import com.github.getcurrentthread.soopapi.api.SOOPLive;
import com.github.getcurrentthread.soopapi.api.model.AuthCookie;
import com.github.getcurrentthread.soopapi.api.model.LiveDetail;
import com.github.getcurrentthread.soopapi.config.SOOPChatConfig;
import com.github.getcurrentthread.soopapi.decoder.MessageDispatcher;
import com.github.getcurrentthread.soopapi.event.ChatEvent;
import com.github.getcurrentthread.soopapi.event.model.*;
import com.github.getcurrentthread.soopapi.exception.AdultBroadcastException;
import com.github.getcurrentthread.soopapi.exception.ConnectionException;
import com.github.getcurrentthread.soopapi.util.SOOPChatUtils;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

@Tag("integration")
class SOOPChatClientRealConnectionTest {

    static final String BROAD_LIST_URL =
            "https://static.file.sooplive.co.kr/pc/ko_KR/main_broad_list_with_adult_json.js";
    static final int TOP_N = 10;
    static final int MONITOR_SECONDS = 150;
    static final String LOG_DIR = "test-logs";

    /**
     * 방송 목록·방송 정보 조회 하나에 주는 시간. 19금 익명 연결 테스트의 connectToChat() 대기(15초)보다 짧아야 조회 실패가 시간 초과로 가려지지
     * 않는다. 19금 로그인 테스트의 로그인과 채팅 세션(connectionTimeout)에도 쓴다.
     */
    static final Duration LOOKUP_TIMEOUT = Duration.ofSeconds(10);

    /** 익명 조회로 19금 여부를 확인해 볼 목록상 19금 방송의 최대 개수. */
    static final int ADULT_CANDIDATES = 10;

    /** 19금 로그인 테스트에 쓸 연령 인증된 계정. 둘 다 있어야 그 테스트가 돈다. */
    static final String TEST_ID_ENV = "SOOP_TEST_ID";

    static final String TEST_PW_ENV = "SOOP_TEST_PW";

    /** 비어 있지 않은 값. 맞지 않으면 JUnit이 건너뛴 이유에 값을 그대로 적으므로, 줄바꿈이 든 값도 맞도록 (?s)를 붙인다. */
    static final String NON_EMPTY = "(?s).+";

    /** INFO 미만의 기록도 파일에 남기는 로거의 이름 접두사(이 라이브러리와 테스트). */
    static final String LIBRARY_LOGGERS = "com.github.getcurrentthread.soopapi.";

    static final Logger logger = Logger.getLogger(SOOPChatClientRealConnectionTest.class.getName());
    static final Logger rootLogger = Logger.getLogger("");
    static Handler[] originalHandlers;
    static Level originalLevel;
    static FileHandler normalHandler;
    static FileHandler errorHandler;

    static final ConcurrentHashMap<String, AtomicLong> eventCounters = new ConcurrentHashMap<>();
    static final AtomicLong totalEvents = new AtomicLong(0);

    static final int MAX_SAMPLES_PER_TYPE = 5;
    static final ConcurrentHashMap<ChatEvent, List<BaseEvent>> eventSamples =
            new ConcurrentHashMap<>();

    /** 이 개수 안에서, 이 시간 안에 같은 raw가 다시 오면 중복 전달로 본다. 사용자가 같은 말을 반복한 경우는 보통 더 멀리 떨어져 있다. */
    static final int DUPLICATE_WINDOW_MESSAGES = 10;

    static final long DUPLICATE_WINDOW_MILLIS = 1_000;

    /** 채팅 raw와 수신 시각. */
    record SeenChat(String raw, long receivedAt) {}

    /** 스트림 하나의 전달 특성(순서, 동시 실행, 종료 후 유입, 재연결 중복)을 관찰한다. */
    static final class StreamProbe {
        final String bid;
        final AtomicInteger inFlight = new AtomicInteger();
        final AtomicInteger maxInFlight = new AtomicInteger();
        final AtomicLong lastChatTimestamp = new AtomicLong();
        final AtomicLong chatOrderViolations = new AtomicLong();
        final AtomicLong keepAlives = new AtomicLong();
        final AtomicLong eventsAfterDisconnect = new AtomicLong();
        final ConcurrentHashMap<ChatEvent, AtomicLong> lifecycle = new ConcurrentHashMap<>();
        final Deque<SeenChat> recentChatsAfterForce = new ArrayDeque<>();
        final AtomicLong duplicateChatsAfterForce = new AtomicLong();
        volatile boolean forced;
        volatile boolean disconnected;
        volatile CountDownLatch joined = new CountDownLatch(1);

        StreamProbe(String bid) {
            this.bid = bid;
        }

        void record(BaseEvent event) {
            int now = inFlight.incrementAndGet();
            maxInFlight.accumulateAndGet(now, Math::max);
            try {
                ChatEvent type = event.eventType();
                switch (type) {
                    case JOIN_CHANNEL -> joined.countDown();
                    case KEEP_ALIVE -> keepAlives.incrementAndGet();
                    case CHAT_MESSAGE -> {
                        long prev = lastChatTimestamp.getAndSet(event.timestamp());
                        if (event.timestamp() < prev) {
                            chatOrderViolations.incrementAndGet();
                        }
                        if (forced) {
                            checkDuplicate(event.raw());
                        }
                    }
                    default -> {}
                }
                // 세션이 끝난 뒤(DISCONNECTED 이후)에는 어떤 이벤트도 오면 안 된다.
                if (disconnected) {
                    eventsAfterDisconnect.incrementAndGet();
                }
                if (type == ChatEvent.DISCONNECTED
                        || type == ChatEvent.RECONNECTING
                        || type == ChatEvent.RECONNECTED
                        || type == ChatEvent.JOIN_CHANNEL) {
                    lifecycle.computeIfAbsent(type, _ -> new AtomicLong()).incrementAndGet();
                }
                if (type == ChatEvent.DISCONNECTED) {
                    disconnected = true;
                }
            } finally {
                inFlight.decrementAndGet();
            }
        }

        private synchronized void checkDuplicate(String raw) {
            long now = System.currentTimeMillis();
            for (SeenChat seen : recentChatsAfterForce) {
                if (seen.raw().equals(raw) && now - seen.receivedAt() <= DUPLICATE_WINDOW_MILLIS) {
                    duplicateChatsAfterForce.incrementAndGet();
                    break;
                }
            }
            recentChatsAfterForce.addLast(new SeenChat(raw, now));
            if (recentChatsAfterForce.size() > DUPLICATE_WINDOW_MESSAGES) {
                recentChatsAfterForce.removeFirst();
            }
        }

        long lifecycleCount(ChatEvent type) {
            AtomicLong c = lifecycle.get(type);
            return c != null ? c.get() : 0;
        }
    }

    @BeforeAll
    static void setupLogging() throws IOException {
        Path logDir = Path.of(LOG_DIR);
        Files.createDirectories(logDir);

        System.setProperty(
                "java.util.logging.SimpleFormatter.format",
                "[%1$tF %1$tT] [%4$-7s] %2$s: %5$s%6$s%n");

        // 이 클래스 동안만 root logger를 파일로 돌리고, 끝나면 원래 설정으로 되돌린다.
        originalLevel = rootLogger.getLevel();
        originalHandlers = rootLogger.getHandlers();
        rootLogger.setLevel(Level.ALL);
        for (var handler : originalHandlers) {
            rootLogger.removeHandler(handler);
        }

        normalHandler = new FileHandler(LOG_DIR + "/normal.log", false);
        normalHandler.setLevel(Level.ALL);
        normalHandler.setFormatter(new SimpleFormatter());
        normalHandler.setFilter(SOOPChatClientRealConnectionTest::isRecorded);
        rootLogger.addHandler(normalHandler);

        errorHandler = new FileHandler(LOG_DIR + "/error.log", false);
        errorHandler.setLevel(Level.WARNING);
        errorHandler.setFormatter(new SimpleFormatter());
        rootLogger.addHandler(errorHandler);
    }

    @AfterAll
    static void tearDownLogging() {
        if (normalHandler != null) {
            rootLogger.removeHandler(normalHandler);
            normalHandler.close();
        }
        if (errorHandler != null) {
            rootLogger.removeHandler(errorHandler);
            errorHandler.close();
        }
        if (originalHandlers != null) {
            for (var handler : originalHandlers) {
                rootLogger.addHandler(handler);
            }
        }
        rootLogger.setLevel(originalLevel);
    }

    /**
     * INFO 미만은 이 라이브러리와 테스트의 기록만 파일에 남긴다. JUnit은 조건 평가 결과를 FINER로 남기는데, 켜진
     * {@code @EnabledIfEnvironmentVariable}의 결과에는 환경 변수 값(SOOP_TEST_PW 포함)이 그대로 들어간다.
     */
    static boolean isRecorded(LogRecord record) {
        String name = record.getLoggerName();
        return record.getLevel().intValue() >= Level.INFO.intValue()
                || (name != null && name.startsWith(LIBRARY_LOGGERS));
    }

    @Test
    void testTop10RealConnection() throws Exception {
        // === 1단계: 방송 목록 fetch & 상위 10개 추출 ===
        logger.info("=== Fetching broadcast list ===");

        List<Map<String, Object>> topStreamers = fetchTopStreamers();
        logger.info("=== Target streamers (" + topStreamers.size() + ") ===");
        for (int i = 0; i < topStreamers.size(); i++) {
            var s = topStreamers.get(i);
            logger.info(
                    String.format(
                            "#%d BID=%s, BNO=%s, viewers=%s",
                            i + 1, s.get("user_id"), s.get("broad_no"), s.get("total_view_cnt")));
        }

        // === 2단계: SOOPChatClient 생성 & 이벤트 리스너 등록 ===
        List<SOOPChatClient> clients = new ArrayList<>();
        List<StreamProbe> probes = new ArrayList<>();

        for (var streamer : topStreamers) {
            String bid = (String) streamer.get("user_id");
            String bno = String.valueOf(streamer.get("broad_no"));
            StreamProbe probe = new StreamProbe(bid);

            SOOPChatConfig config = new SOOPChatConfig.Builder().bid(bid).bno(bno).build();
            SOOPChatClient client = new SOOPChatClient(config);

            for (ChatEvent eventType : ChatEvent.values()) {
                client.on(
                        eventType,
                        (BaseEvent event) -> {
                            probe.record(event);
                            if (event.eventType() == ChatEvent.RAW) {
                                return;
                            }
                            String key = bid + ":" + event.eventType().name();
                            eventCounters
                                    .computeIfAbsent(key, _ -> new AtomicLong(0))
                                    .incrementAndGet();
                            totalEvents.incrementAndGet();

                            List<BaseEvent> samples =
                                    eventSamples.computeIfAbsent(
                                            event.eventType(), _ -> new CopyOnWriteArrayList<>());
                            if (samples.size() < MAX_SAMPLES_PER_TYPE) {
                                samples.add(event);
                            }

                            logger.fine(formatEventLog(bid, event));
                        });
            }

            clients.add(client);
            probes.add(probe);
        }

        // === 3단계: 동시 연결 ===
        // connectToChat()의 future는 세션이 끝날 때 완료되므로, 연결 성공은 JOIN_CHANNEL 수신으로 판정한다.
        logger.info("=== Starting concurrent connections ===");
        for (int i = 0; i < clients.size(); i++) {
            String bid = probes.get(i).bid;
            clients.get(i)
                    .connectToChat()
                    .whenComplete(
                            (v, ex) -> {
                                if (ex != null) {
                                    logger.log(Level.WARNING, "[" + bid + "] Session failed", ex);
                                }
                            });
        }

        long joinDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        int connectedCount = 0;
        for (StreamProbe probe : probes) {
            long remaining = Math.max(0, joinDeadline - System.nanoTime());
            if (probe.joined.await(remaining, TimeUnit.NANOSECONDS)) {
                connectedCount++;
            } else {
                logger.warning("[" + probe.bid + "] JOIN_CHANNEL not received within 30s");
            }
        }
        logger.info(
                "=== Connection complete: "
                        + connectedCount
                        + "/"
                        + clients.size()
                        + " joined ===");

        assertTrue(connectedCount >= 5, "At least 5 joins required, actual: " + connectedCount);

        // JOIN_CHANNEL을 받은 스트림에서는 ready()가 완료돼야 한다(그 사이 재연결 중이면 다시 들어갈 때 완료).
        // 그 사이 세션이 끝났으면(방송 종료, 서버 종료) 실패하는 것이 정상이다. DISCONNECTED는 lane에서 뒤따라 온다.
        List<String> notReady = new ArrayList<>();
        for (int i = 0; i < clients.size(); i++) {
            StreamProbe probe = probes.get(i);
            if (probe.joined.getCount() == 0
                    && !completesWithin(clients.get(i).ready(), 10, probe.bid + " ready()")
                    && !disconnectsWithin(probe, 5)) {
                notReady.add(probe.bid);
            }
        }
        logger.info("=== ready() completed for joined streams, except: " + notReady + " ===");

        // === 4단계: ping 주기(기본 60초)가 두 번 넘게 지나도록 관찰 (10초마다 상태 확인) ===
        logger.info("=== Monitoring started (" + MONITOR_SECONDS + "s) ===");

        long deadline = System.currentTimeMillis() + MONITOR_SECONDS * 1_000L;
        int logIntervalSec = 0;
        while (System.currentTimeMillis() < deadline) {
            Thread.sleep(10_000);
            logIntervalSec += 10;

            long activeClients = clients.stream().filter(SOOPChatClient::isConnected).count();
            logger.info(
                    String.format(
                            "=== [%ds elapsed] Connected clients: %d, Total events received: %d ===",
                            logIntervalSec, activeClients, totalEvents.get()));
        }

        logger.info("Monitoring ended - Total events received: " + totalEvents.get());

        // === 5단계: forceReconnect 한 번 — 세션 유지, 중복 전달 여부 관찰 ===
        int forcedIdx = 0;
        while (probes.get(forcedIdx).joined.getCount() != 0) {
            forcedIdx++;
        }
        StreamProbe forcedProbe = probes.get(forcedIdx);
        long disconnectsBeforeForce = forcedProbe.lifecycleCount(ChatEvent.DISCONNECTED);
        long joinsBeforeForce = forcedProbe.lifecycleCount(ChatEvent.JOIN_CHANNEL);
        forcedProbe.joined = new CountDownLatch(1);
        forcedProbe.forced = true;
        logger.info("=== forceReconnect on [" + forcedProbe.bid + "] ===");
        clients.get(forcedIdx).forceReconnect();
        // forceReconnect는 새 연결로 바꿔 끼운 뒤 반환하므로, 이 ready()는 새 연결이 채널에 들어갈 때 완료돼야 한다.
        CompletableFuture<Void> readyAfterForce = clients.get(forcedIdx).ready();
        boolean rejoined = forcedProbe.joined.await(30, TimeUnit.SECONDS);
        boolean readyAgain =
                completesWithin(readyAfterForce, 30, forcedProbe.bid + " ready() after force");
        Thread.sleep(15_000);
        logger.info(
                String.format(
                        "[%s] forceReconnect: rejoined=%s, ready=%s, JOIN_CHANNEL=+%d,"
                                + " DISCONNECTED=+%d, RECONNECTING=%d, RECONNECTED=%d,"
                                + " duplicate chats=%d",
                        forcedProbe.bid,
                        rejoined,
                        readyAgain,
                        forcedProbe.lifecycleCount(ChatEvent.JOIN_CHANNEL) - joinsBeforeForce,
                        forcedProbe.lifecycleCount(ChatEvent.DISCONNECTED) - disconnectsBeforeForce,
                        forcedProbe.lifecycleCount(ChatEvent.RECONNECTING),
                        forcedProbe.lifecycleCount(ChatEvent.RECONNECTED),
                        forcedProbe.duplicateChatsAfterForce.get()));

        long disconnectsDuringForce =
                forcedProbe.lifecycleCount(ChatEvent.DISCONNECTED) - disconnectsBeforeForce;

        // === 6단계: 모든 클라이언트 disconnect 후 유입 이벤트 관찰 ===
        for (int i = 0; i < clients.size(); i++) {
            try {
                clients.get(i).disconnect();
            } catch (Exception e) {
                logger.log(Level.WARNING, "Disconnect error", e);
            }
        }
        Thread.sleep(3_000);

        // === 7단계: 정리 & 통계 ===
        logger.info("=== Final statistics ===");
        for (StreamProbe probe : probes) {
            long chatCount = getCount(probe.bid, "CHAT_MESSAGE");
            long totalForBid =
                    eventCounters.entrySet().stream()
                            .filter(e -> e.getKey().startsWith(probe.bid + ":"))
                            .mapToLong(e -> e.getValue().get())
                            .sum();
            logger.info(
                    String.format(
                            "[%s] events=%d, chats=%d, maxConcurrentListeners=%d,"
                                    + " chatOrderViolations=%d, keepAlives=%d,"
                                    + " eventsAfterDisconnect=%d, DISCONNECTED=%d",
                            probe.bid,
                            totalForBid,
                            chatCount,
                            probe.maxInFlight.get(),
                            probe.chatOrderViolations.get(),
                            probe.keepAlives.get(),
                            probe.eventsAfterDisconnect.get(),
                            probe.lifecycleCount(ChatEvent.DISCONNECTED)));
        }
        logger.info("Total events received: " + totalEvents.get());

        // === 8단계: 파싱 검증 리포트 생성 ===
        generateParsingReport();

        // === 9단계: 전달 보장 판정 ===
        List<Executable> checks = new ArrayList<>();
        checks.add(
                () ->
                        assertTrue(
                                notReady.isEmpty(),
                                "ready() should complete for joined streams: " + notReady));
        checks.add(() -> assertTrue(rejoined, "forceReconnect should rejoin"));
        checks.add(
                () -> assertTrue(readyAgain, "ready() should complete again after forceReconnect"));
        checks.add(
                () ->
                        assertEquals(
                                0,
                                disconnectsDuringForce,
                                "forceReconnect must not emit DISCONNECTED"));
        checks.add(
                () ->
                        assertEquals(
                                1,
                                forcedProbe.lifecycleCount(ChatEvent.RECONNECTED),
                                "forceReconnect should emit RECONNECTED once"));
        checks.add(
                () ->
                        assertEquals(
                                0,
                                forcedProbe.duplicateChatsAfterForce.get(),
                                "No chat should be delivered twice after forceReconnect"));
        for (StreamProbe probe : probes) {
            checks.add(
                    () ->
                            assertEquals(
                                    1,
                                    probe.maxInFlight.get(),
                                    "[" + probe.bid + "] listeners must not run concurrently"));
            checks.add(
                    () ->
                            assertEquals(
                                    1,
                                    probe.lifecycleCount(ChatEvent.DISCONNECTED),
                                    "[" + probe.bid + "] one DISCONNECTED per session"));
            checks.add(
                    () ->
                            assertEquals(
                                    0,
                                    probe.eventsAfterDisconnect.get(),
                                    "[" + probe.bid + "] no events after DISCONNECTED"));
        }
        assertAll(checks);

        logger.info("=== Test complete ===");
    }

    /**
     * 19금 방송 하나에 익명으로 연결하면 방송 정보 조회에서 {@link AdultBroadcastException}으로 끝나야 한다. 재시도 없이
     * DISCONNECTED가 한 번 온다. 익명 조회가 19금으로 막히는 방송이 지금 없으면 건너뛴다(skipped).
     */
    @Test
    void testAdultBroadcastFailsAnonymously() throws Exception {
        Map<String, Object> target = adultOnlyTarget();

        String bid = (String) target.get("user_id");
        String bno = String.valueOf(target.get("broad_no"));
        List<DisconnectedEvent> disconnects = new CopyOnWriteArrayList<>();
        AtomicInteger reconnecting = new AtomicInteger();

        SOOPChatConfig config =
                new SOOPChatConfig.Builder()
                        .bid(bid)
                        .bno(bno)
                        .connectionTimeout(LOOKUP_TIMEOUT)
                        .build();
        try (SOOPChatClient client = new SOOPChatClient(config)) {
            client.on(ChatEvent.DISCONNECTED, (DisconnectedEvent e) -> disconnects.add(e));
            client.on(
                    ChatEvent.RECONNECTING,
                    (ReconnectingEvent e) -> reconnecting.incrementAndGet());

            ExecutionException ex =
                    assertThrows(
                            ExecutionException.class,
                            () -> client.connectToChat().get(15, TimeUnit.SECONDS));
            Throwable failure = SOOPChatUtils.unwrapCompletionException(ex.getCause());
            // 늦게 오는 DISCONNECTED·RECONNECTING이 있는지 잠시 더 본다.
            Thread.sleep(2_000);

            boolean typedCause =
                    failure instanceof ConnectionException
                            && failure.getCause() instanceof AdultBroadcastException;
            boolean causedByError =
                    !disconnects.isEmpty() && disconnects.getFirst().causedByError();
            boolean reasonMentions19 =
                    !disconnects.isEmpty() && disconnects.getFirst().reason().contains("19+");
            logger.info(
                    String.format(
                            "[%s] 19+ anonymous probe: typedCause=%s, DISCONNECTED=%d,"
                                    + " causedByError=%s, reasonMentions19=%s, RECONNECTING=%d",
                            bid,
                            typedCause,
                            disconnects.size(),
                            causedByError,
                            reasonMentions19,
                            reconnecting.get()));

            assertAll(
                    () ->
                            assertTrue(
                                    typedCause,
                                    "connectToChat() should fail with ConnectionException"
                                            + " caused by AdultBroadcastException"),
                    () -> assertEquals(1, disconnects.size(), "one DISCONNECTED per session"),
                    () -> assertTrue(causedByError, "DISCONNECTED should be caused by an error"),
                    () -> assertTrue(reasonMentions19, "DISCONNECTED reason should explain 19+"),
                    () -> assertEquals(0, reconnecting.get(), "19+ lookup must not be retried"));
        }
    }

    /**
     * 연령 인증된 계정의 로그인 쿠키로는 19금 방송의 방송 정보 조회와 채팅 연결이 된다. 계정은 환경 변수 SOOP_TEST_ID·SOOP_TEST_PW로 받고, 둘 중
     * 하나라도 없거나 비어 있으면 건너뛴다(skipped). 익명 조회가 19금으로 막히는 방송이 지금 없어도 로그인하기 전에 건너뛴다. 채팅·귓말은 보내지 않는다. 계정
     * ID·비밀번호·쿠키·LOGIN의 userId·채팅 본문은 로그와 실패 메시지에 남기지 않고 개수와 참·거짓만 남긴다.
     */
    @Test
    @EnabledIfEnvironmentVariable(named = TEST_ID_ENV, matches = NON_EMPTY)
    @EnabledIfEnvironmentVariable(named = TEST_PW_ENV, matches = NON_EMPTY)
    void testAdultBroadcastWithAgeVerifiedLogin() throws Exception {
        Map<String, Object> target = adultOnlyTarget();

        String password = System.getenv(TEST_PW_ENV);
        AuthCookie cookie = signIn(System.getenv(TEST_ID_ENV), password);
        logger.info("=== 19+ login: signed in ===");

        String bid = (String) target.get("user_id");
        String bno = String.valueOf(target.get("broad_no"));
        String ftk = detailWithLogin(bid, bno, cookie);
        logger.info("[" + bid + "] 19+ login: detail() with the login cookie succeeded");

        AtomicInteger joins = new AtomicInteger();
        AtomicBoolean loginWithUserId = new AtomicBoolean();
        AtomicLong chats = new AtomicLong();
        AtomicInteger reconnecting = new AtomicInteger();
        List<DisconnectedEvent> disconnects = new CopyOnWriteArrayList<>();
        boolean ready;

        SOOPChatConfig config =
                new SOOPChatConfig.Builder()
                        .authCookie(cookie)
                        .bid(bid)
                        .bno(bno)
                        .connectionTimeout(LOOKUP_TIMEOUT)
                        .build();
        // 수신 패킷 원문을 FINE으로 남기는 로그를 이 세션 동안 끈다. LOGIN 응답에는 계정 ID가, 채팅 패킷에는 본문이 들어 있다.
        Logger dispatcherLogger = Logger.getLogger(MessageDispatcher.class.getName());
        Level dispatcherLevel = dispatcherLogger.getLevel();
        dispatcherLogger.setLevel(Level.INFO);
        try (SOOPChatClient client = new SOOPChatClient(config)) {
            client.on(ChatEvent.JOIN_CHANNEL, (JoinChannelEvent e) -> joins.incrementAndGet());
            // userId는 계정 ID이므로 값은 두지 않고 비어 있지 않은지만 기록한다.
            client.on(
                    ChatEvent.LOGIN,
                    (LoginEvent e) -> {
                        if (e.userId() != null && !e.userId().isEmpty()) {
                            loginWithUserId.set(true);
                        }
                    });
            client.on(ChatEvent.CHAT_MESSAGE, (ChatMessageEvent e) -> chats.incrementAndGet());
            client.on(
                    ChatEvent.RECONNECTING,
                    (ReconnectingEvent e) -> reconnecting.incrementAndGet());
            client.on(ChatEvent.DISCONNECTED, (DisconnectedEvent e) -> disconnects.add(e));

            client.connectToChat();
            ready = completesWithin(client.ready(), 15, bid + " ready()");
            // 채팅이 들어오는 동안 잠시 머문다. 이 테스트는 채팅을 보내지 않는다.
            Thread.sleep(10_000);
            client.disconnect();

            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (disconnects.isEmpty() && System.nanoTime() < deadline) {
                Thread.sleep(50);
            }
            // 늦게 오는 DISCONNECTED·RECONNECTING이 있는지 잠시 더 본다.
            Thread.sleep(2_000);
        } finally {
            dispatcherLogger.setLevel(dispatcherLevel);
        }

        boolean clientInitiated =
                !disconnects.isEmpty() && disconnects.getFirst().isClientInitiated();
        boolean causedByError = !disconnects.isEmpty() && disconnects.getFirst().causedByError();
        logger.info(
                String.format(
                        "[%s] 19+ login session: ready=%s, JOIN_CHANNEL=%d, loginWithUserId=%s,"
                                + " chats=%d, RECONNECTING=%d, DISCONNECTED=%d,"
                                + " clientInitiated=%s, causedByError=%s",
                        bid,
                        ready,
                        joins.get(),
                        loginWithUserId.get(),
                        chats.get(),
                        reconnecting.get(),
                        disconnects.size(),
                        clientInitiated,
                        causedByError));

        Map<String, String> secrets = new LinkedHashMap<>();
        secrets.put("The password", password);
        secrets.put("AuthTicket", cookie.authTicket());
        secrets.put("UserTicket", cookie.userTicket());
        secrets.put("_au", cookie.au());
        secrets.put("RDB", cookie.rdb());
        secrets.put("FTK", ftk);
        assertAll(
                () -> assertTrue(ready, "ready() should complete within 15s"),
                () -> assertTrue(joins.get() >= 1, "JOIN_CHANNEL should arrive"),
                () -> assertTrue(loginWithUserId.get(), "LOGIN should carry the signed-in user"),
                () -> assertEquals(0, reconnecting.get(), "no RECONNECTING"),
                () -> assertEquals(1, disconnects.size(), "one DISCONNECTED per session"),
                () -> assertTrue(clientInitiated, "DISCONNECTED should be client-initiated"),
                () -> assertFalse(causedByError, "DISCONNECTED should not be caused by an error"),
                () -> assertNotLogged(secrets));
    }

    /** SOOPAuth로 로그인해 인증된 쿠키를 돌려준다. 실패 메시지에는 예외 종류만 남긴다(계정 정보나 서버 응답이 실리지 않도록 원인도 붙이지 않는다). */
    private static AuthCookie signIn(String id, String password) throws Exception {
        AuthCookie cookie;
        try (SOOPHttpClient http = new SOOPHttpClient(LOOKUP_TIMEOUT)) {
            cookie = new SOOPAuth(http).signIn(id, password).get(15, TimeUnit.SECONDS);
        } catch (ExecutionException e) {
            return fail("Sign-in failed: " + e.getCause().getClass().getSimpleName());
        }
        assertTrue(cookie.isAuthenticated(), "Sign-in did not return an authenticated AuthCookie");
        return cookie;
    }

    /** 로그인 쿠키로 방송 정보를 조회해 FTK를 돌려준다. 실패하면 예외 종류만 남기고 테스트를 실패시킨다. */
    private static String detailWithLogin(String bid, String bno, AuthCookie cookie)
            throws Exception {
        try (SOOPHttpClient http = new SOOPHttpClient(LOOKUP_TIMEOUT)) {
            LiveDetail detail =
                    new SOOPLive(http).detail(bid, bno, cookie).get(15, TimeUnit.SECONDS);
            assertEquals(1, detail.result(), "detail() with the login cookie should succeed");
            return detail.ftk();
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            return fail(
                    "["
                            + bid
                            + "] detail() with the login cookie failed: "
                            + cause.getClass().getSimpleName()
                            + (cause instanceof AdultBroadcastException
                                    ? " (is the test account age-verified?)"
                                    : ""));
        }
    }

    /**
     * 로그 파일(normal.log·error.log)에 자격 증명 값이 남지 않았는지 본다. 값은 실패 메시지에 싣지 않는다. 짧은 값은 다른 기록과 우연히 겹칠 수 있어
     * 8자 이상만 본다.
     */
    private static void assertNotLogged(Map<String, String> secrets) throws IOException {
        normalHandler.flush();
        errorHandler.flush();
        List<Executable> checks = new ArrayList<>();
        for (String file : List.of("normal.log", "error.log")) {
            String log =
                    new String(Files.readAllBytes(Path.of(LOG_DIR, file)), StandardCharsets.UTF_8);
            secrets.forEach(
                    (name, value) -> {
                        if (value != null && value.length() >= 8) {
                            checks.add(
                                    () ->
                                            assertFalse(
                                                    log.contains(value),
                                                    name + " was written to " + file));
                        }
                    });
        }
        assertAll(checks);
    }

    /** 목록에서 익명 조회가 실제로 19금으로 막히는 방송 하나를 고른다. 지금 없으면 건너뛴다(skipped). */
    private Map<String, Object> adultOnlyTarget() throws Exception {
        List<Map<String, Object>> adults =
                fetchBroadcasts().stream()
                        .filter(SOOPChatClientRealConnectionTest::isAdult)
                        .toList();
        logger.info("=== 19+ broadcasts in the list: " + adults.size() + " ===");
        Map<String, Object> target = firstAdultOnly(adults);
        if (target == null) {
            logger.info("No broadcast rejects anonymous requests as 19+; skipping the 19+ probe");
        }
        assumeTrue(target != null, "No live broadcast rejects anonymous requests as 19+");
        return target;
    }

    /**
     * 방송 목록의 broad_grade는 캐시된 값이라 지금의 19금 여부와 다를 수 있다. 앞에서부터 {@link #ADULT_CANDIDATES}개까지 익명으로 조회해
     * {@link AdultBroadcastException}으로 실패하는 첫 방송을 돌려준다. 없으면 null.
     */
    private static Map<String, Object> firstAdultOnly(List<Map<String, Object>> adults) {
        try (SOOPHttpClient http = new SOOPHttpClient(LOOKUP_TIMEOUT)) {
            SOOPLive live = new SOOPLive(http);
            for (Map<String, Object> broad :
                    adults.subList(0, Math.min(ADULT_CANDIDATES, adults.size()))) {
                String bid = (String) broad.get("user_id");
                try {
                    live.detail(bid, String.valueOf(broad.get("broad_no"))).join();
                    logger.info("[" + bid + "] listed as 19+ but open to anonymous requests");
                } catch (CompletionException e) {
                    if (e.getCause() instanceof AdultBroadcastException) {
                        return broad;
                    }
                    logger.log(Level.INFO, "[" + bid + "] lookup failed", e.getCause());
                }
            }
        }
        return null;
    }

    /** future가 제한 시간 안에 정상 완료되면 true. 실패나 시간 초과는 로그로 남긴다. */
    private static boolean completesWithin(CompletableFuture<?> future, long seconds, String what) {
        try {
            future.get(seconds, TimeUnit.SECONDS);
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        } catch (Exception e) {
            logger.log(Level.WARNING, what + " did not complete", e);
            return false;
        }
    }

    /** 제한 시간 안에 스트림이 DISCONNECTED를 받으면(세션이 끝났으면) true. */
    private static boolean disconnectsWithin(StreamProbe probe, long seconds)
            throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds);
        while (!probe.disconnected && System.nanoTime() < deadline) {
            Thread.sleep(50);
        }
        if (probe.disconnected) {
            logger.info("[" + probe.bid + "] session ended before ready(); not counted");
        }
        return probe.disconnected;
    }

    /** 익명 연결은 19금 방송의 채팅에 들어갈 수 없으므로 목록에서 빼고 시청자 수 상위 {@link #TOP_N}개를 고른다. */
    private List<Map<String, Object>> fetchTopStreamers() throws Exception {
        List<Map<String, Object>> broads = fetchBroadcasts();
        List<Map<String, Object>> open = broads.stream().filter(broad -> !isAdult(broad)).toList();
        logger.info("Skipped 19+ broadcasts: " + (broads.size() - open.size()));
        return open.subList(0, Math.min(TOP_N, open.size()));
    }

    /** 방송 목록의 broad_grade가 19면 19금 방송이다. 목록에 따라 문자열이나 숫자로 온다. */
    private static boolean isAdult(Map<String, Object> broad) {
        Object grade = broad.get("broad_grade");
        if (grade instanceof Number n) return n.intValue() == 19;
        return "19".equals(String.valueOf(grade));
    }

    /** 방송 목록 전체를 시청자 수 내림차순으로 돌려준다. */
    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> fetchBroadcasts() throws Exception {
        HttpClient httpClient = HttpClient.newBuilder().connectTimeout(LOOKUP_TIMEOUT).build();
        HttpRequest request =
                HttpRequest.newBuilder()
                        .uri(URI.create(BROAD_LIST_URL))
                        .timeout(LOOKUP_TIMEOUT)
                        .header("User-Agent", "Mozilla/5.0")
                        .GET()
                        .build();

        HttpResponse<String> response =
                httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        String body = response.body();

        Gson gson = new Gson();
        Map<String, Object> json =
                gson.fromJson(body, new TypeToken<Map<String, Object>>() {}.getType());

        List<Map<String, Object>> broads = (List<Map<String, Object>>) json.get("broad");
        if (broads == null || broads.isEmpty()) {
            throw new RuntimeException("Cannot fetch broadcast list (no broad array)");
        }

        logger.info("Total broadcasts: " + broads.size());

        broads.sort(
                Comparator.comparingInt(
                                (Map<String, Object> m) -> {
                                    Object cnt = m.get("total_view_cnt");
                                    if (cnt instanceof Number n) return n.intValue();
                                    return Integer.parseInt(String.valueOf(cnt));
                                })
                        .reversed());

        return broads;
    }

    private static String formatEventLog(String bid, BaseEvent event) {
        return switch (event) {
            case ChatMessageEvent chat ->
                    "[" + bid + "] CHAT_MESSAGE: " + chat.senderNickname() + ": " + chat.message();
            case SendBalloonEvent balloon ->
                    "["
                            + bid
                            + "] SEND_BALLOON: "
                            + balloon.senderNickname()
                            + " → "
                            + balloon.count();
            default -> {
                String raw = event.raw();
                String summary = (raw != null && raw.length() > 100) ? raw.substring(0, 100) : raw;
                yield "[" + bid + "] " + event.eventType().name() + ": " + summary;
            }
        };
    }

    private static long getCount(String bid, String eventType) {
        AtomicLong counter = eventCounters.get(bid + ":" + eventType);
        return counter != null ? counter.get() : 0;
    }

    // === 파싱 검증 관련 ===

    record FieldResult(String fieldName, Object value, boolean passed, String reason) {}

    private static List<FieldResult> validateEvent(BaseEvent event) {
        List<FieldResult> results = new ArrayList<>();
        results.add(checkNonNull("eventType", event.eventType()));
        results.add(checkNonNullString("raw", event.raw()));
        results.add(checkPositive("timestamp", event.timestamp()));

        switch (event) {
            case ChatMessageEvent e -> {
                results.add(checkNonNullString("message", e.message()));
                results.add(checkNonNullString("senderId", e.senderId()));
                results.add(checkNonNullString("senderNickname", e.senderNickname()));
                results.add(checkNonNullString("senderFlag", e.senderFlag()));
                results.add(checkNonNullString("subscriptionMonth", e.subscriptionMonth()));
                results.add(checkAny("type", e.type()));
                results.add(checkAny("chatLang", e.chatLang()));
                results.add(checkOptionalString("randomNicknameColor", e.randomNicknameColor()));
                results.add(
                        checkOptionalString(
                                "randomNicknameColorDarkmode", e.randomNicknameColorDarkmode()));
            }
            case ChatUserEvent e -> {
                results.add(checkAny("type", e.type()));
                results.add(checkNonNullCollection("userList", e.userList()));
                if (e.userList() != null && !e.userList().isEmpty()) {
                    var first = e.userList().getFirst();
                    results.add(checkNonNullString("userList[0].id", first.id()));
                    results.add(checkNonNullString("userList[0].nickname", first.nickname()));
                    results.add(checkNonNullString("userList[0].flag", first.flag()));
                }
            }
            case ChuserExtendEvent e -> {
                results.add(checkNonNullMap("userStatus", e.userStatus()));
                if (e.userStatus() != null && !e.userStatus().isEmpty()) {
                    var firstEntry = e.userStatus().entrySet().iterator().next();
                    results.add(checkNonNullString("firstKey", firstEntry.getKey()));
                    results.add(checkNonNull("firstValue", firstEntry.getValue()));
                }
            }
            case SendBalloonEvent e -> {
                results.add(checkNonNullString("bjId", e.bjId()));
                results.add(checkNonNullString("senderId", e.senderId()));
                results.add(checkNonNullString("senderNickname", e.senderNickname()));
                results.add(checkPositive("count", e.count()));
                results.add(checkAny("fanOrder", e.fanOrder()));
                results.add(checkNonNullString("fileName", e.fileName()));
                results.add(checkOptionalString("ttsData", e.ttsData()));
            }
            case OGQEmoticonEvent e -> {
                results.add(checkNonNullString("chatNo", e.chatNo()));
                results.add(checkOptionalString("message", e.message()));
                results.add(checkNonNullString("groupId", e.groupId()));
                results.add(checkNonNullString("subId", e.subId()));
                results.add(checkNonNullString("version", e.version()));
                results.add(checkNonNullString("userInfo", e.userInfo()));
                results.add(checkOptionalString("color", e.color()));
                results.add(checkOptionalString("chatLang", e.chatLang()));
                results.add(checkOptionalString("type", e.type()));
            }
            case LoginEvent e -> {
                results.add(checkOptionalString("userId", e.userId()));
                results.add(checkNonNullString("userFlag", e.userFlag()));
            }
            case JoinChannelEvent e -> {
                results.add(checkNonNullString("chatNo", e.chatNo()));
                results.add(checkNonNullString("bjId", e.bjId()));
                results.add(checkAny("maxSubBjCount", e.maxSubBjCount()));
                results.add(checkOptionalString("familyNickname", e.familyNickname()));
                results.add(checkNonNullString("userFlag", e.userFlag()));
            }
            case SetUserFlagEvent e -> {
                results.add(checkNonNullString("oldFlag", e.oldFlag()));
                results.add(checkNonNullString("userId", e.userId()));
                results.add(checkNonNullString("userNickname", e.userNickname()));
                results.add(checkNonNullString("newFlag", e.newFlag()));
            }
            case TranslationStateEvent e -> {
                results.add(checkAny("state", e.state()));
            }
            case KickMsgStateEvent e -> {
                results.add(checkNonNullString("chatNo", e.chatNo()));
                results.add(checkAny("isHideKickMessage", e.isHideKickMessage()));
            }
            case BanWordEvent e -> {
                results.add(checkNonNullString("replaceWord", e.replaceWord()));
                results.add(checkNonNullCollection("banWordList", e.banWordList()));
            }
            case BjNoticeEvent e -> {
                results.add(checkAny("show", e.show()));
                results.add(checkNonNullString("message", e.message()));
            }
            case SetSubBjEvent e -> {
                results.add(checkNonNullString("userId", e.userId()));
                results.add(checkNonNullString("flag", e.flag()));
                results.add(checkAny("hide", e.hide()));
                results.add(checkNonNullString("nickname", e.nickname()));
            }
            case EmoticonTicketEvent e -> {
                results.add(checkAny("value", e.value()));
            }
            case UnknownEvent e -> {
                // 원래 서비스 코드가 실려야 한다. 모르는 코드는 모두 양수다(0은 KEEP_ALIVE).
                results.add(checkPositive("code", e.code()));
                results.add(checkNonNullString("originalMessage", e.originalMessage()));
            }
            default -> {
                // 기본 필드는 이미 검증 완료; 처리되지 않은 타입에 대해 WARN
            }
        }
        return results;
    }

    private static FieldResult checkNonNull(String name, Object value) {
        if (value == null) {
            return new FieldResult(name, null, false, "FAIL: value is null");
        }
        return new FieldResult(name, value, true, "OK");
    }

    private static FieldResult checkNonNullString(String name, String value) {
        if (value == null) {
            return new FieldResult(name, null, false, "FAIL: string is null");
        }
        if (value.isEmpty()) {
            return new FieldResult(name, value, false, "FAIL: string is empty");
        }
        return new FieldResult(name, value, true, "OK");
    }

    private static FieldResult checkOptionalString(String name, String value) {
        if (value == null || value.isEmpty()) {
            return new FieldResult(name, value, true, "WARN: optional field is null/empty");
        }
        return new FieldResult(name, value, true, "OK");
    }

    private static FieldResult checkPositive(String name, long value) {
        if (value <= 0) {
            return new FieldResult(
                    name, value, false, "FAIL: value is not positive (" + value + ")");
        }
        return new FieldResult(name, value, true, "OK");
    }

    private static FieldResult checkAny(String name, Object value) {
        return new FieldResult(name, value, true, "OK");
    }

    private static FieldResult checkNonNullCollection(String name, Collection<?> value) {
        if (value == null) {
            return new FieldResult(name, null, false, "FAIL: collection is null");
        }
        if (value.isEmpty()) {
            return new FieldResult(name, value, false, "FAIL: collection is empty");
        }
        return new FieldResult(name, value, true, "OK: size=" + value.size());
    }

    private static FieldResult checkNonNullMap(String name, Map<?, ?> value) {
        if (value == null) {
            return new FieldResult(name, null, false, "FAIL: map is null");
        }
        if (value.isEmpty()) {
            return new FieldResult(name, value, false, "FAIL: map is empty");
        }
        return new FieldResult(name, value, true, "OK: size=" + value.size());
    }

    private static void generateParsingReport() {
        Path reportPath = Path.of(LOG_DIR, "parsing-report.log");
        logger.info("=== Step 6: Generating parsing validation report ===");

        Map<String, int[]> summaryMap = new TreeMap<>();
        int totalOk = 0, totalWarn = 0, totalFail = 0;

        try (PrintWriter pw = new PrintWriter(Files.newBufferedWriter(reportPath))) {
            pw.println("========================================");
            pw.println("  SOOP Chat Event Parsing Report");
            pw.println("========================================");
            pw.println();

            var sortedEntries =
                    eventSamples.entrySet().stream()
                            .sorted(Comparator.comparing(e -> e.getKey().name()))
                            .toList();

            for (var entry : sortedEntries) {
                ChatEvent eventType = entry.getKey();
                List<BaseEvent> samples = entry.getValue();
                int[] counts = {0, 0, 0}; // 성공, 경고, 실패

                pw.printf(
                        "--- %s (%s) - %d samples ---%n",
                        eventType.name(), eventType.getDescription(), samples.size());

                for (int i = 0; i < samples.size(); i++) {
                    BaseEvent sample = samples.get(i);
                    List<FieldResult> results = validateEvent(sample);

                    pw.printf("  Sample #%d [%s]:%n", i + 1, sample.getClass().getSimpleName());
                    for (FieldResult fr : results) {
                        String status;
                        if (!fr.passed()) {
                            status = "FAIL";
                            counts[2]++;
                        } else if (fr.reason().startsWith("WARN")) {
                            status = "WARN";
                            counts[1]++;
                        } else {
                            status = "OK";
                            counts[0]++;
                        }

                        String displayValue = formatValue(fr.value());
                        pw.printf("    %-30s = %-35s [%s]%n", fr.fieldName(), displayValue, status);
                    }
                    pw.println();
                }

                summaryMap.put(eventType.name(), counts);
                totalOk += counts[0];
                totalWarn += counts[1];
                totalFail += counts[2];
            }

            pw.println("========================================");
            pw.println("  SUMMARY");
            pw.println("========================================");
            pw.printf("%-30s %5s %6s %6s%n", "EVENT TYPE", "OK", "WARN", "FAIL");
            pw.println("-".repeat(49));

            for (var entry : summaryMap.entrySet()) {
                int[] c = entry.getValue();
                pw.printf("%-30s %5d %6d %6d%n", entry.getKey(), c[0], c[1], c[2]);
            }

            pw.println("-".repeat(49));
            pw.printf("%-30s %5d %6d %6d%n", "TOTAL", totalOk, totalWarn, totalFail);
            pw.println();

            logger.info("Parsing report generated: " + reportPath.toAbsolutePath());
            logger.info(
                    String.format(
                            "SUMMARY - OK: %d, WARN: %d, FAIL: %d", totalOk, totalWarn, totalFail));
        } catch (IOException e) {
            logger.log(Level.SEVERE, "Failed to generate parsing report", e);
        }
    }

    private static String formatValue(Object value) {
        if (value == null) return "null";
        String str = value.toString();
        if (str.length() > 35) {
            return str.substring(0, 32) + "...";
        }
        return str;
    }
}
