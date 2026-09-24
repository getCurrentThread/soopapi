package com.github.getcurrentthread.soopapi;

import java.io.BufferedOutputStream;
import java.io.DataOutputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import com.github.getcurrentthread.soopapi.client.SOOPChatClient;
import com.github.getcurrentthread.soopapi.config.SOOPChatConfig;
import com.github.getcurrentthread.soopapi.constant.SOOPConstants;
import com.github.getcurrentthread.soopapi.event.ChatEvent;
import com.github.getcurrentthread.soopapi.event.model.ChatMessageEvent;
import com.github.getcurrentthread.soopapi.event.model.RawEvent;
import com.github.getcurrentthread.soopapi.util.SOOPChatUtils;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

public class DataCollector {

    /**
     * 현재 상위 방송 목록 (실시간 갱신 JSON 파일).
     *
     * <p>{@code play.sooplive.com}을 거치지 않으므로 {@code AbroadChk=FAIL} 쿠키 문제가 발생하지 않습니다.
     */
    private static final String BROAD_LIST_URL =
            "https://static.file.sooplive.co.kr/pc/ko_KR/main_broad_list_with_adult_json.js";

    /** 픽스처 파일 저장 경로 (lib/ 디렉터리 기준으로 실행 시 올바른 경로) */
    private static final String FIXTURES_DIR = "src/test/resources/fixtures";

    /** 타입별 최대 수집 패킷 수 */
    private static final int MAX_PACKETS_PER_TYPE = 30;

    public static void main(String[] args) throws Exception {
        if (args.length > 0 && args[0].equals("--fixtures")) {
            collectFixtures();
        } else {
            collectMessages();
        }
    }

    // ── 기존 메시지 수집 ──────────────────────────────────────────────────────

    private static void collectMessages() throws Exception {
        System.out.println("Fetching top 10 streamers...");
        List<BidBno> top10 = fetchTopStreamers(10);
        System.out.println("Top 10 streamers: " + top10.stream().map(BidBno::bid).toList());

        List<StreamerData> collectedData = new CopyOnWriteArrayList<>();
        CountDownLatch latch = new CountDownLatch(top10.size());

        for (BidBno streamer : top10) {
            new Thread(
                            () -> {
                                try {
                                    collectMessagesFromStreamer(streamer, collectedData, latch);
                                } catch (Exception e) {
                                    System.err.println(
                                            "Error collecting for "
                                                    + streamer.bid()
                                                    + ": "
                                                    + e.getMessage());
                                    latch.countDown();
                                }
                            })
                    .start();
        }

        System.out.println("Collecting messages (this may take a few minutes)...");
        latch.await(2, TimeUnit.MINUTES);

        saveData(collectedData);
        System.out.println("Data saved to messages.json");
        System.exit(0);
    }

    private static void collectMessagesFromStreamer(
            BidBno streamer, List<StreamerData> collectedData, CountDownLatch latch) {
        System.out.println("Connecting to " + streamer.bid() + "...");
        SOOPChatConfig config =
                new SOOPChatConfig.Builder().bid(streamer.bid()).bno(streamer.bno()).build();
        SOOPChatClient chat = new SOOPChatClient(config);

        List<String> messages = new CopyOnWriteArrayList<>();

        chat.on(
                ChatEvent.CHAT_MESSAGE,
                (ChatMessageEvent e) -> {
                    if (messages.size() < 10) {
                        messages.add(e.senderNickname() + ": " + e.message());
                        if (messages.size() >= 10) {
                            System.out.println("Collected 10 messages from " + streamer.bid());
                            chat.close();
                        }
                    }
                });

        CompletableFuture<Void> chatFuture = chat.connectToChat();

        try {
            chatFuture.get(30, TimeUnit.SECONDS);
        } catch (Exception e) {
            System.err.println("Stopped collecting for " + streamer.bid() + " (timeout or closed)");
        } finally {
            collectedData.add(new StreamerData(streamer.bid(), new ArrayList<>(messages)));
            latch.countDown();
            chat.close();
        }
    }

    private static void saveData(List<StreamerData> data) throws IOException {
        Gson gson = new GsonBuilder().setPrettyPrinting().create();
        try (FileWriter writer = new FileWriter("messages.json")) {
            gson.toJson(data, writer);
        }
    }

    // ── raw 픽스처 수집 ───────────────────────────────────────────────────────

    /**
     * 각 ChatEvent 타입별 raw 패킷을 최대 {@value MAX_PACKETS_PER_TYPE}개 수집하여 {@value
     * FIXTURES_DIR}/{TYPE_NAME}.bin 파일로 저장합니다.
     *
     * <p>반드시 {@code lib/} 디렉터리를 기준으로 실행해야 합니다.
     *
     * <p>저장된 파일은 로컬 분석용이며 단위 테스트는 읽지 않습니다. 새 패킷 형식을 확인하면 값을 합성 placeholder로 바꿔 {@code
     * decoder/SyntheticPackets}에 옮겨 적습니다.
     */
    private static void collectFixtures() throws Exception {
        int streamerCount = 100;
        int durationMinutes = 10;
        System.out.printf("Fetching top %d streamers...%n", streamerCount);
        List<BidBno> topStreamers = fetchTopStreamers(streamerCount);
        System.out.println("Top streamers: " + topStreamers.stream().map(BidBno::bid).toList());

        ConcurrentHashMap<ChatEvent, List<String>> fixtureMap = new ConcurrentHashMap<>();
        for (ChatEvent event : ChatEvent.values()) {
            if (event.getCode() >= 0) {
                fixtureMap.put(event, new CopyOnWriteArrayList<>());
            }
        }

        CountDownLatch latch = new CountDownLatch(topStreamers.size());

        for (BidBno streamer : topStreamers) {
            new Thread(
                            () -> {
                                try {
                                    collectRawPackets(streamer, fixtureMap, latch, durationMinutes);
                                } catch (Exception e) {
                                    System.err.println(
                                            "Error collecting from "
                                                    + streamer.bid()
                                                    + ": "
                                                    + e.getMessage());
                                    latch.countDown();
                                }
                            })
                    .start();
        }

        System.out.printf("Collecting raw packets for up to %d minutes...%n", durationMinutes);
        latch.await(durationMinutes, TimeUnit.MINUTES);

        saveFixtures(fixtureMap);
        System.out.println("Fixtures saved to " + FIXTURES_DIR);
        System.exit(0);
    }

    private static void collectRawPackets(
            BidBno streamer,
            ConcurrentHashMap<ChatEvent, List<String>> fixtureMap,
            CountDownLatch latch,
            int durationMinutes) {
        System.out.println("Connecting to " + streamer.bid() + " for raw collection...");

        // BNO를 직접 제공하여 play.sooplive.com 방문을 건너뜀
        // → AbroadChk=FAIL 쿠키 문제 방지
        SOOPChatConfig config =
                new SOOPChatConfig.Builder().bid(streamer.bid()).bno(streamer.bno()).build();
        SOOPChatClient chat = new SOOPChatClient(config);

        chat.on(
                ChatEvent.RAW,
                (RawEvent e) -> {
                    String raw = e.raw();
                    int sepIdx = raw.indexOf(SOOPConstants.F_CHAR);
                    if (sepIdx < 0) return;

                    String header = raw.substring(0, sepIdx);
                    int code = SOOPChatUtils.parseServiceCode(header);
                    ChatEvent type = ChatEvent.fromCode(code);

                    if (type.getCode() < 0) return; // RAW, DISCONNECTED 등 메타 이벤트 제외

                    List<String> packets = fixtureMap.get(type);
                    if (packets != null && packets.size() < MAX_PACKETS_PER_TYPE) {
                        packets.add(raw);
                    }
                });

        CompletableFuture<Void> chatFuture = chat.connectToChat();

        try {
            chatFuture.get(durationMinutes, TimeUnit.MINUTES);
        } catch (Exception e) {
            System.err.println("Stopped raw collection for " + streamer.bid());
        } finally {
            latch.countDown();
            chat.close();
        }
    }

    /**
     * 수집된 패킷을 타입별 .bin 파일로 저장합니다.
     *
     * <p>파일 포맷: [4바이트 big-endian 길이][UTF-8 패킷 바이트] 반복
     */
    private static void saveFixtures(ConcurrentHashMap<ChatEvent, List<String>> fixtureMap)
            throws IOException {
        Path fixturesDir = Path.of(FIXTURES_DIR);
        Files.createDirectories(fixturesDir);

        int totalFiles = 0;
        for (Map.Entry<ChatEvent, List<String>> entry : fixtureMap.entrySet()) {
            List<String> packets = entry.getValue();
            if (packets.isEmpty()) continue;

            Path file = fixturesDir.resolve(entry.getKey().name() + ".bin");
            try (DataOutputStream dos =
                    new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(file)))) {
                for (String packet : packets) {
                    byte[] bytes = packet.getBytes(StandardCharsets.UTF_8);
                    dos.writeInt(bytes.length);
                    dos.write(bytes);
                }
            }
            System.out.println("  " + entry.getKey().name() + ": " + packets.size() + " packet(s)");
            totalFiles++;
        }
        System.out.println("Total: " + totalFiles + " fixture file(s) written.");
    }

    // ── 공통 유틸리티 ─────────────────────────────────────────────────────────

    /**
     * 현재 상위 방송 중인 스트리머 n명의 BID와 BNO를 반환합니다.
     *
     * <p>{@code static.file.sooplive.co.kr} 에서 실시간 방송 목록을 가져옵니다. 이 URL은 {@code play.sooplive.com}과
     * 달리 {@code AbroadChk=FAIL} 쿠키를 설정하지 않습니다.
     */
    @SuppressWarnings("unchecked")
    private static List<BidBno> fetchTopStreamers(int count) throws Exception {
        HttpClient client = HttpClient.newHttpClient();
        HttpRequest request =
                HttpRequest.newBuilder()
                        .uri(URI.create(BROAD_LIST_URL))
                        .header("User-Agent", "Mozilla/5.0")
                        .GET()
                        .build();
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

        String body = response.body();
        if (body == null || body.isEmpty()) {
            System.err.println("Response body is empty");
            return new ArrayList<>();
        }

        JsonObject json = JsonParser.parseString(body).getAsJsonObject();
        JsonArray broads = json.getAsJsonArray("broad");
        if (broads == null || broads.isEmpty()) {
            System.err.println("No broadcasts found in response");
            return new ArrayList<>();
        }

        // 시청자 수 기준 내림차순 정렬 후 상위 count 개 선택
        List<JsonObject> sorted = new ArrayList<>();
        for (var elem : broads) {
            sorted.add(elem.getAsJsonObject());
        }
        sorted.sort(
                (a, b) -> {
                    long ca = parseLong(a, "total_view_cnt");
                    long cb = parseLong(b, "total_view_cnt");
                    return Long.compare(cb, ca);
                });

        List<BidBno> result = new ArrayList<>();
        for (JsonObject o : sorted) {
            if (result.size() >= count) break;
            String bid = o.get("user_id").getAsString();
            String bno = o.get("broad_no").getAsString();
            result.add(new BidBno(bid, bno));
        }
        return result;
    }

    private static long parseLong(JsonObject obj, String field) {
        try {
            return obj.get(field).getAsLong();
        } catch (Exception e) {
            return 0L;
        }
    }

    public record BidBno(String bid, String bno) {}

    public record StreamerData(String streamerId, List<String> messages) {}
}
