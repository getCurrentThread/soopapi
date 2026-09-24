package com.github.getcurrentthread.soopapi.decoder;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.*;

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;

import com.github.getcurrentthread.soopapi.decoder.factory.DefaultMessageDecoderFactory;
import com.github.getcurrentthread.soopapi.decoder.message.IMessageDecoder;
import com.github.getcurrentthread.soopapi.event.ChatEvent;
import com.github.getcurrentthread.soopapi.event.EventEmitter;
import com.github.getcurrentthread.soopapi.event.EventListener;
import com.github.getcurrentthread.soopapi.event.model.*;

/**
 * 각 .bin fixture 파일을 개별적으로 로드하여 디코딩된 이벤트 필드 값을 검증·출력하는 테스트.
 *
 * <p>각 테스트는:
 *
 * <ol>
 *   <li>해당 fixture 파일에서 첫 번째 패킷을 로드한다.
 *   <li>MessageDispatcher를 통해 디코딩한다.
 *   <li>모든 필드 값을 "[FIXTURE] field=value" 형식으로 출력한다.
 *   <li>핵심 필드에 대한 assertion을 수행한다.
 * </ol>
 *
 * <p>fixture 파일이 없는 경우 테스트는 자동으로 스킵된다.
 */
class FixtureDecoderDetailTest {

    // ─── 공통 인프라 ──────────────────────────────────────────────

    private static final ExecutorService DIRECT_EXECUTOR =
            new java.util.concurrent.AbstractExecutorService() {
                @Override
                public void shutdown() {}

                @Override
                public List<Runnable> shutdownNow() {
                    return List.of();
                }

                @Override
                public boolean isShutdown() {
                    return false;
                }

                @Override
                public boolean isTerminated() {
                    return false;
                }

                @Override
                public boolean awaitTermination(long t, TimeUnit u) {
                    return true;
                }

                @Override
                public void execute(Runnable r) {
                    r.run();
                }
            };

    /** 지정된 fixture 파일에서 첫 번째 패킷 문자열을 반환한다. 파일이 없으면 null 반환. */
    private String loadFirstPacket(String fixtureName) {
        URL url = getClass().getClassLoader().getResource("fixtures/" + fixtureName + ".bin");
        if (url == null) return null;
        try {
            Path path = Path.of(url.toURI());
            try (DataInputStream dis =
                    new DataInputStream(new BufferedInputStream(Files.newInputStream(path)))) {
                if (dis.available() == 0) return null;
                int length = dis.readInt();
                byte[] bytes = new byte[length];
                dis.readFully(bytes);
                return new String(bytes, StandardCharsets.UTF_8);
            }
        } catch (Exception e) {
            return null;
        }
    }

    /** 모든 패킷을 로드한다. */
    private List<String> loadAllPackets(String fixtureName) {
        URL url = getClass().getClassLoader().getResource("fixtures/" + fixtureName + ".bin");
        if (url == null) return List.of();
        try {
            Path path = Path.of(url.toURI());
            java.util.List<String> packets = new java.util.ArrayList<>();
            try (DataInputStream dis =
                    new DataInputStream(new BufferedInputStream(Files.newInputStream(path)))) {
                while (dis.available() > 0) {
                    int length = dis.readInt();
                    byte[] bytes = new byte[length];
                    dis.readFully(bytes);
                    packets.add(new String(bytes, StandardCharsets.UTF_8));
                }
            }
            return packets;
        } catch (Exception e) {
            return List.of();
        }
    }

    /** 패킷을 디코딩하고 지정 이벤트 타입의 이벤트를 캡처해 반환한다. */
    @SuppressWarnings("unchecked")
    private <T extends BaseEvent> T decode(ChatEvent eventType, String rawPacket) {
        EventEmitter emitter = new EventEmitter();
        Map<ChatEvent, IMessageDecoder> decoders =
                new DefaultMessageDecoderFactory().createDecoders();
        MessageDispatcher dispatcher = new MessageDispatcher(decoders, DIRECT_EXECUTOR, emitter);

        AtomicReference<BaseEvent> received = new AtomicReference<>();
        emitter.on(eventType, (EventListener<BaseEvent>) received::set);
        dispatcher.dispatchMessage(rawPacket);
        return (T) received.get();
    }

    private void printField(String fixture, String name, Object value) {
        System.out.printf("[%s] %s=%s%n", fixture, name, value);
    }

    // ─── 개별 Fixture 테스트 ──────────────────────────────────────

    @Test
    void test_ADCON_EFFECT() {
        String raw = loadFirstPacket("ADCON_EFFECT");
        assumeTrue(raw != null, "ADCON_EFFECT fixture 없음");

        AdconEffectEvent e = decode(ChatEvent.ADCON_EFFECT, raw);
        assertNotNull(e, "ADCON_EFFECT 이벤트 디코딩 실패");

        printField("ADCON_EFFECT", "chatNo", e.chatNo());
        printField("ADCON_EFFECT", "bjId", e.bjId());
        printField("ADCON_EFFECT", "senderId", e.senderId());
        printField("ADCON_EFFECT", "senderNickname", e.senderNickname());
        printField("ADCON_EFFECT", "message", e.message());
        printField("ADCON_EFFECT", "message2", e.message2());
        printField("ADCON_EFFECT", "title", e.title());
        printField("ADCON_EFFECT", "urlImg", e.urlImg());
        printField("ADCON_EFFECT", "urlDefault", e.urlDefault());
        printField("ADCON_EFFECT", "adconCount", e.adconCount());
        printField("ADCON_EFFECT", "fanOrder", e.fanOrder());
        printField("ADCON_EFFECT", "isTopFan", e.isTopFan());
        printField("ADCON_EFFECT", "isFanChief", e.isFanChief());
        printField("ADCON_EFFECT", "isSubRoom", e.isSubRoom());
        printField("ADCON_EFFECT", "eventType", e.eventType());

        assertEquals(ChatEvent.ADCON_EFFECT, e.eventType());
        assertNotNull(e.raw());
        assertTrue(e.timestamp() > 0);
    }

    @Test
    void test_BAN_WORD() {
        String raw = loadFirstPacket("BAN_WORD");
        assumeTrue(raw != null, "BAN_WORD fixture 없음");

        BanWordEvent e = decode(ChatEvent.BAN_WORD, raw);
        assertNotNull(e, "BAN_WORD 이벤트 디코딩 실패");

        printField("BAN_WORD", "replaceWord", e.replaceWord());
        printField("BAN_WORD", "banWordList.size", e.banWordList().size());
        printField("BAN_WORD", "banWordList", e.banWordList());
        printField("BAN_WORD", "eventType", e.eventType());

        assertEquals(ChatEvent.BAN_WORD, e.eventType());
        assertNotNull(e.replaceWord());
        assertNotNull(e.banWordList());
    }

    @Test
    void test_BJ_NOTICE() {
        String raw = loadFirstPacket("BJ_NOTICE");
        assumeTrue(raw != null, "BJ_NOTICE fixture 없음");

        BjNoticeEvent e = decode(ChatEvent.BJ_NOTICE, raw);
        assertNotNull(e, "BJ_NOTICE 이벤트 디코딩 실패");

        printField("BJ_NOTICE", "show", e.show());
        printField("BJ_NOTICE", "message", e.message());
        printField("BJ_NOTICE", "eventType", e.eventType());

        assertEquals(ChatEvent.BJ_NOTICE, e.eventType());
        assertNotNull(e.message());
    }

    @Test
    void test_BJ_STICKER_ITEM() {
        String raw = loadFirstPacket("BJ_STICKER_ITEM");
        assumeTrue(raw != null, "BJ_STICKER_ITEM fixture 없음");

        BjStickerItemEvent e = decode(ChatEvent.BJ_STICKER_ITEM, raw);
        assertNotNull(e, "BJ_STICKER_ITEM 이벤트 디코딩 실패");

        printField("BJ_STICKER_ITEM", "type", e.type());
        printField("BJ_STICKER_ITEM", "eventType", e.eventType());

        assertEquals(ChatEvent.BJ_STICKER_ITEM, e.eventType());
    }

    @Test
    void test_CHAT_MESSAGE() {
        String raw = loadFirstPacket("CHAT_MESSAGE");
        assumeTrue(raw != null, "CHAT_MESSAGE fixture 없음");

        ChatMessageEvent e = decode(ChatEvent.CHAT_MESSAGE, raw);
        assertNotNull(e, "CHAT_MESSAGE 이벤트 디코딩 실패");

        printField("CHAT_MESSAGE", "message", e.message());
        printField("CHAT_MESSAGE", "senderId", e.senderId());
        printField("CHAT_MESSAGE", "type", e.type());
        printField("CHAT_MESSAGE", "chatLang", e.chatLang());
        printField("CHAT_MESSAGE", "senderNickname", e.senderNickname());
        printField("CHAT_MESSAGE", "senderFlag", e.senderFlag());
        printField("CHAT_MESSAGE", "subscriptionMonth", e.subscriptionMonth());
        printField("CHAT_MESSAGE", "randomNicknameColor", e.randomNicknameColor());
        printField("CHAT_MESSAGE", "randomNicknameColorDarkmode", e.randomNicknameColorDarkmode());
        printField("CHAT_MESSAGE", "eventType", e.eventType());

        assertEquals(ChatEvent.CHAT_MESSAGE, e.eventType());
        assertNotNull(e.message());
        assertNotNull(e.senderId());
        assertNotNull(e.senderNickname());
    }

    @Test
    void test_CHAT_USER() {
        String raw = loadFirstPacket("CHAT_USER");
        assumeTrue(raw != null, "CHAT_USER fixture 없음");

        ChatUserEvent e = decode(ChatEvent.CHAT_USER, raw);
        assertNotNull(e, "CHAT_USER 이벤트 디코딩 실패");

        printField("CHAT_USER", "type", e.type());
        printField("CHAT_USER", "userList.size", e.userList().size());
        for (int i = 0; i < e.userList().size(); i++) {
            var u = e.userList().get(i);
            printField("CHAT_USER", "userList[" + i + "].id", u.id());
            printField("CHAT_USER", "userList[" + i + "].nickname", u.nickname());
            printField("CHAT_USER", "userList[" + i + "].flag", u.flag());
        }
        printField("CHAT_USER", "eventType", e.eventType());

        assertEquals(ChatEvent.CHAT_USER, e.eventType());
        assertNotNull(e.userList());
    }

    @Test
    void test_CHUSER_EXTEND() {
        String raw = loadFirstPacket("CHUSER_EXTEND");
        assumeTrue(raw != null, "CHUSER_EXTEND fixture 없음");

        ChuserExtendEvent e = decode(ChatEvent.CHUSER_EXTEND, raw);
        assertNotNull(e, "CHUSER_EXTEND 이벤트 디코딩 실패");

        printField("CHUSER_EXTEND", "userStatus.size", e.userStatus().size());
        e.userStatus()
                .forEach(
                        (userId, statusMap) -> {
                            printField("CHUSER_EXTEND", "userStatus[" + userId + "]", statusMap);
                        });
        printField("CHUSER_EXTEND", "eventType", e.eventType());

        assertEquals(ChatEvent.CHUSER_EXTEND, e.eventType());
        assertNotNull(e.userStatus());
    }

    @Test
    void test_EMOTICON_TICKET() {
        String raw = loadFirstPacket("EMOTICON_TICKET");
        assumeTrue(raw != null, "EMOTICON_TICKET fixture 없음");

        EmoticonTicketEvent e = decode(ChatEvent.EMOTICON_TICKET, raw);
        assertNotNull(e, "EMOTICON_TICKET 이벤트 디코딩 실패");

        printField("EMOTICON_TICKET", "value", e.value());
        printField("EMOTICON_TICKET", "eventType", e.eventType());

        assertEquals(ChatEvent.EMOTICON_TICKET, e.eventType());
    }

    @Test
    void test_FOLLOW_ITEM() {
        String raw = loadFirstPacket("FOLLOW_ITEM");
        assumeTrue(raw != null, "FOLLOW_ITEM fixture 없음");

        FollowItemEvent e = decode(ChatEvent.FOLLOW_ITEM, raw);
        assertNotNull(e, "FOLLOW_ITEM 이벤트 디코딩 실패");

        printField("FOLLOW_ITEM", "chatNo", e.chatNo());
        printField("FOLLOW_ITEM", "recvId", e.recvId());
        printField("FOLLOW_ITEM", "sendId", e.sendId());
        printField("FOLLOW_ITEM", "sendNick", e.sendNick());
        printField("FOLLOW_ITEM", "type", e.type());
        printField("FOLLOW_ITEM", "eventType", e.eventType());

        assertEquals(ChatEvent.FOLLOW_ITEM, e.eventType());
        assertNotNull(e.recvId());
        assertNotNull(e.sendId());
    }

    @Test
    void test_FOLLOW_ITEM_EFFECT() {
        String raw = loadFirstPacket("FOLLOW_ITEM_EFFECT");
        assumeTrue(raw != null, "FOLLOW_ITEM_EFFECT fixture 없음");

        FollowItemEffectEvent e = decode(ChatEvent.FOLLOW_ITEM_EFFECT, raw);
        assertNotNull(e, "FOLLOW_ITEM_EFFECT 이벤트 디코딩 실패");

        printField("FOLLOW_ITEM_EFFECT", "bjId", e.bjId());
        printField("FOLLOW_ITEM_EFFECT", "sendId", e.sendId());
        printField("FOLLOW_ITEM_EFFECT", "sendNick", e.sendNick());
        printField("FOLLOW_ITEM_EFFECT", "month", e.month());
        printField("FOLLOW_ITEM_EFFECT", "chatNo", e.chatNo());
        printField("FOLLOW_ITEM_EFFECT", "eventType", e.eventType());

        assertEquals(ChatEvent.FOLLOW_ITEM_EFFECT, e.eventType());
        assertNotNull(e.bjId());
        assertNotNull(e.sendId());
    }

    @Test
    void test_ICE_MODE() {
        String raw = loadFirstPacket("ICE_MODE");
        assumeTrue(raw != null, "ICE_MODE fixture 없음");

        IceModeEvent e = decode(ChatEvent.ICE_MODE, raw);
        assertNotNull(e, "ICE_MODE 이벤트 디코딩 실패");

        printField("ICE_MODE", "iceMode", e.iceMode());
        printField("ICE_MODE", "eventType", e.eventType());

        assertEquals(ChatEvent.ICE_MODE, e.eventType());
    }

    @Test
    void test_ICE_MODE_EX() {
        String raw = loadFirstPacket("ICE_MODE_EX");
        assumeTrue(raw != null, "ICE_MODE_EX fixture 없음");

        IceModeExEvent e = decode(ChatEvent.ICE_MODE_EX, raw);
        assertNotNull(e, "ICE_MODE_EX 이벤트 디코딩 실패");

        printField("ICE_MODE_EX", "iceMode", e.iceMode());
        printField("ICE_MODE_EX", "freezeType", e.freezeType());
        printField("ICE_MODE_EX", "balloonLimitCount", e.balloonLimitCount());
        printField("ICE_MODE_EX", "subscriptionLimitCount", e.subscriptionLimitCount());
        printField("ICE_MODE_EX", "eventType", e.eventType());

        assertEquals(ChatEvent.ICE_MODE_EX, e.eventType());
    }

    @Test
    void test_ITEM_DROPS() {
        String raw = loadFirstPacket("ITEM_DROPS");
        assumeTrue(raw != null, "ITEM_DROPS fixture 없음");

        ItemDropsEvent e = decode(ChatEvent.ITEM_DROPS, raw);
        assertNotNull(e, "ITEM_DROPS 이벤트 디코딩 실패");

        printField("ITEM_DROPS", "bjId", e.bjId());
        printField("ITEM_DROPS", "dropsName", e.dropsName());
        printField("ITEM_DROPS", "dropsMsg", e.dropsMsg());
        printField("ITEM_DROPS", "dropsImgUrl", e.dropsImgUrl());
        printField("ITEM_DROPS", "eventType", e.eventType());

        assertEquals(ChatEvent.ITEM_DROPS, e.eventType());
        assertNotNull(e.bjId());
    }

    @Test
    void test_JOIN_CHANNEL() {
        String raw = loadFirstPacket("JOIN_CHANNEL");
        assumeTrue(raw != null, "JOIN_CHANNEL fixture 없음");

        JoinChannelEvent e = decode(ChatEvent.JOIN_CHANNEL, raw);
        assertNotNull(e, "JOIN_CHANNEL 이벤트 디코딩 실패");

        printField("JOIN_CHANNEL", "chatNo", e.chatNo());
        printField("JOIN_CHANNEL", "bjId", e.bjId());
        printField("JOIN_CHANNEL", "maxSubBjCount", e.maxSubBjCount());
        printField("JOIN_CHANNEL", "familyNickname", e.familyNickname());
        printField("JOIN_CHANNEL", "userFlag", e.userFlag());
        printField("JOIN_CHANNEL", "eventType", e.eventType());

        assertEquals(ChatEvent.JOIN_CHANNEL, e.eventType());
        assertNotNull(e.chatNo());
        assertNotNull(e.bjId());
    }

    @Test
    void test_KICK_MSG_STATE() {
        String raw = loadFirstPacket("KICK_MSG_STATE");
        assumeTrue(raw != null, "KICK_MSG_STATE fixture 없음");

        KickMsgStateEvent e = decode(ChatEvent.KICK_MSG_STATE, raw);
        assertNotNull(e, "KICK_MSG_STATE 이벤트 디코딩 실패");

        printField("KICK_MSG_STATE", "chatNo", e.chatNo());
        printField("KICK_MSG_STATE", "isHideKickMessage", e.isHideKickMessage());
        printField("KICK_MSG_STATE", "eventType", e.eventType());

        assertEquals(ChatEvent.KICK_MSG_STATE, e.eventType());
        assertNotNull(e.chatNo());
    }

    @Test
    void test_LOGIN() {
        String raw = loadFirstPacket("LOGIN");
        assumeTrue(raw != null, "LOGIN fixture 없음");

        LoginEvent e = decode(ChatEvent.LOGIN, raw);
        assertNotNull(e, "LOGIN 이벤트 디코딩 실패");

        printField("LOGIN", "userId", e.userId());
        printField("LOGIN", "userFlag", e.userFlag());
        printField("LOGIN", "eventType", e.eventType());

        assertEquals(ChatEvent.LOGIN, e.eventType());
        assertNotNull(e.userId());
        assertNotNull(e.userFlag());
    }

    @Test
    void test_MISSION() {
        String raw = loadFirstPacket("MISSION");
        assumeTrue(raw != null, "MISSION fixture 없음");

        MissionEvent e = decode(ChatEvent.MISSION, raw);
        assertNotNull(e, "MISSION 이벤트 디코딩 실패");

        printField("MISSION", "data.keys", e.data().keySet());
        e.data().forEach((k, v) -> printField("MISSION", "data." + k, v));
        printField("MISSION", "eventType", e.eventType());

        assertEquals(ChatEvent.MISSION, e.eventType());
        assertNotNull(e.data());
        assertFalse(e.data().isEmpty(), "미션 데이터가 비어 있음");
    }

    @Test
    void test_MISSION_SETTLE() {
        String raw = loadFirstPacket("MISSION_SETTLE");
        assumeTrue(raw != null, "MISSION_SETTLE fixture 없음");

        MissionSettleEvent e = decode(ChatEvent.MISSION_SETTLE, raw);
        assertNotNull(e, "MISSION_SETTLE 이벤트 디코딩 실패");

        printField("MISSION_SETTLE", "data.keys", e.data().keySet());
        e.data().forEach((k, v) -> printField("MISSION_SETTLE", "data." + k, v));
        printField("MISSION_SETTLE", "eventType", e.eventType());

        assertEquals(ChatEvent.MISSION_SETTLE, e.eventType());
        assertNotNull(e.data());
    }

    @Test
    void test_OGQ_EMOTICON() {
        String raw = loadFirstPacket("OGQ_EMOTICON");
        assumeTrue(raw != null, "OGQ_EMOTICON fixture 없음");

        OGQEmoticonEvent e = decode(ChatEvent.OGQ_EMOTICON, raw);
        assertNotNull(e, "OGQ_EMOTICON 이벤트 디코딩 실패");

        printField("OGQ_EMOTICON", "chatNo", e.chatNo());
        printField("OGQ_EMOTICON", "message", e.message());
        printField("OGQ_EMOTICON", "groupId", e.groupId());
        printField("OGQ_EMOTICON", "subId", e.subId());
        printField("OGQ_EMOTICON", "version", e.version());
        printField("OGQ_EMOTICON", "userInfo", e.userInfo());
        printField("OGQ_EMOTICON", "color", e.color());
        printField("OGQ_EMOTICON", "chatLang", e.chatLang());
        printField("OGQ_EMOTICON", "type", e.type());
        printField("OGQ_EMOTICON", "eventType", e.eventType());

        assertEquals(ChatEvent.OGQ_EMOTICON, e.eventType());
        assertNotNull(e.chatNo());
    }

    @Test
    void test_OGQ_EMOTICON_GIFT() {
        String raw = loadFirstPacket("OGQ_EMOTICON_GIFT");
        assumeTrue(raw != null, "OGQ_EMOTICON_GIFT fixture 없음");

        GiftOGQEmoticonEvent e = decode(ChatEvent.OGQ_EMOTICON_GIFT, raw);
        assertNotNull(e, "OGQ_EMOTICON_GIFT 이벤트 디코딩 실패");

        printField("OGQ_EMOTICON_GIFT", "senderId", e.senderId());
        printField("OGQ_EMOTICON_GIFT", "senderNick", e.senderNick());
        printField("OGQ_EMOTICON_GIFT", "receivedId", e.receivedId());
        printField("OGQ_EMOTICON_GIFT", "receivedNick", e.receivedNick());
        printField("OGQ_EMOTICON_GIFT", "ogqTitle", e.ogqTitle());
        printField("OGQ_EMOTICON_GIFT", "ogqImageUrl", e.ogqImageUrl());
        printField("OGQ_EMOTICON_GIFT", "eventType", e.eventType());

        assertEquals(ChatEvent.OGQ_EMOTICON_GIFT, e.eventType());
        assertNotNull(e.senderId());
    }

    @Test
    void test_SEND_BALLOON() {
        String raw = loadFirstPacket("SEND_BALLOON");
        assumeTrue(raw != null, "SEND_BALLOON fixture 없음");

        SendBalloonEvent e = decode(ChatEvent.SEND_BALLOON, raw);
        assertNotNull(e, "SEND_BALLOON 이벤트 디코딩 실패");

        printField("SEND_BALLOON", "bjId", e.bjId());
        printField("SEND_BALLOON", "senderId", e.senderId());
        printField("SEND_BALLOON", "senderNickname", e.senderNickname());
        printField("SEND_BALLOON", "count", e.count());
        printField("SEND_BALLOON", "fanOrder", e.fanOrder());
        printField("SEND_BALLOON", "fileName", e.fileName());
        printField("SEND_BALLOON", "isDefault", e.isDefault());
        printField("SEND_BALLOON", "isTopFan", e.isTopFan());
        printField("SEND_BALLOON", "ttsData", e.ttsData());
        printField("SEND_BALLOON", "eventType", e.eventType());

        assertEquals(ChatEvent.SEND_BALLOON, e.eventType());
        assertNotNull(e.bjId());
        assertNotNull(e.senderId());
        assertTrue(e.count() > 0, "풍선 수는 0보다 커야 함");
    }

    @Test
    void test_SEND_QUICK_VIEW() {
        String raw = loadFirstPacket("SEND_QUICK_VIEW");
        assumeTrue(raw != null, "SEND_QUICK_VIEW fixture 없음");

        QuickViewEvent e = decode(ChatEvent.SEND_QUICK_VIEW, raw);
        assertNotNull(e, "SEND_QUICK_VIEW 이벤트 디코딩 실패");

        printField("SEND_QUICK_VIEW", "senderId", e.senderId());
        printField("SEND_QUICK_VIEW", "senderNickname", e.senderNickname());
        printField("SEND_QUICK_VIEW", "receiverId", e.receiverId());
        printField("SEND_QUICK_VIEW", "receiverNickname", e.receiverNickname());
        printField("SEND_QUICK_VIEW", "itemType", e.itemType());
        printField("SEND_QUICK_VIEW", "eventType", e.eventType());

        assertEquals(ChatEvent.SEND_QUICK_VIEW, e.eventType());
        assertNotNull(e.senderId());
    }

    @Test
    void test_SEND_SUBSCRIPTION() {
        String raw = loadFirstPacket("SEND_SUBSCRIPTION");
        assumeTrue(raw != null, "SEND_SUBSCRIPTION fixture 없음");

        SendSubscriptionEvent e = decode(ChatEvent.SEND_SUBSCRIPTION, raw);
        assertNotNull(e, "SEND_SUBSCRIPTION 이벤트 디코딩 실패");

        printField("SEND_SUBSCRIPTION", "senderId", e.senderId());
        printField("SEND_SUBSCRIPTION", "senderNickname", e.senderNickname());
        printField("SEND_SUBSCRIPTION", "receiverId", e.receiverId());
        printField("SEND_SUBSCRIPTION", "receiverNickname", e.receiverNickname());
        printField("SEND_SUBSCRIPTION", "subscriptionId", e.subscriptionId());
        printField("SEND_SUBSCRIPTION", "subscriptionNickname", e.subscriptionNickname());
        printField("SEND_SUBSCRIPTION", "itemType", e.itemType());
        printField("SEND_SUBSCRIPTION", "itemCode", e.itemCode());
        printField("SEND_SUBSCRIPTION", "isSubscription", e.isSubscription());
        printField("SEND_SUBSCRIPTION", "subscriptionType", e.subscriptionType());
        printField("SEND_SUBSCRIPTION", "subscriptionPeriod", e.subscriptionPeriod());
        printField("SEND_SUBSCRIPTION", "subscriptionRemain", e.subscriptionRemain());
        printField("SEND_SUBSCRIPTION", "subscriptionPaycount", e.subscriptionPaycount());
        printField("SEND_SUBSCRIPTION", "eventType", e.eventType());

        assertEquals(ChatEvent.SEND_SUBSCRIPTION, e.eventType());
        assertNotNull(e.senderId());
        assertNotNull(e.receiverId());
    }

    @Test
    void test_SET_BJ_STAT() {
        String raw = loadFirstPacket("SET_BJ_STAT");
        assumeTrue(raw != null, "SET_BJ_STAT fixture 없음");

        SetBjStatEvent e = decode(ChatEvent.SET_BJ_STAT, raw);
        assertNotNull(e, "SET_BJ_STAT 이벤트 디코딩 실패");

        printField("SET_BJ_STAT", "eventType", e.eventType());
        printField(
                "SET_BJ_STAT",
                "raw(truncated)",
                e.raw().length() > 80 ? e.raw().substring(0, 80) + "..." : e.raw());

        assertEquals(ChatEvent.SET_BJ_STAT, e.eventType());
        assertNotNull(e.raw());
    }

    @Test
    void test_SET_DUMB() {
        String raw = loadFirstPacket("SET_DUMB");
        assumeTrue(raw != null, "SET_DUMB fixture 없음");

        SetDumbEvent e = decode(ChatEvent.SET_DUMB, raw);
        assertNotNull(e, "SET_DUMB 이벤트 디코딩 실패");

        printField("SET_DUMB", "userId", e.userId());
        printField("SET_DUMB", "userInfo", e.userInfo());
        printField("SET_DUMB", "dumbTime", e.dumbTime());
        printField("SET_DUMB", "dumbCount", e.dumbCount());
        printField("SET_DUMB", "adminId", e.adminId());
        printField("SET_DUMB", "adminType", e.adminType());
        printField("SET_DUMB", "extraInfo", e.extraInfo());
        printField("SET_DUMB", "userNickname", e.userNickname());
        printField("SET_DUMB", "eventType", e.eventType());

        assertEquals(ChatEvent.SET_DUMB, e.eventType());
        assertNotNull(e.userId());
    }

    @Test
    void test_SET_NICKNAME() {
        String raw = loadFirstPacket("SET_NICKNAME");
        assumeTrue(raw != null, "SET_NICKNAME fixture 없음");

        SetNicknameEvent e = decode(ChatEvent.SET_NICKNAME, raw);
        assertNotNull(e, "SET_NICKNAME 이벤트 디코딩 실패");

        printField("SET_NICKNAME", "userId", e.userId());
        printField("SET_NICKNAME", "newNickname", e.newNickname());
        printField("SET_NICKNAME", "changeType", e.changeType());
        printField("SET_NICKNAME", "flag", e.flag());
        printField("SET_NICKNAME", "oldNickname", e.oldNickname());
        printField("SET_NICKNAME", "eventType", e.eventType());

        assertEquals(ChatEvent.SET_NICKNAME, e.eventType());
        assertNotNull(e.userId());
        assertNotNull(e.newNickname());
    }

    @Test
    void test_SET_SUB_BJ() {
        String raw = loadFirstPacket("SET_SUB_BJ");
        assumeTrue(raw != null, "SET_SUB_BJ fixture 없음");

        SetSubBjEvent e = decode(ChatEvent.SET_SUB_BJ, raw);
        assertNotNull(e, "SET_SUB_BJ 이벤트 디코딩 실패");

        printField("SET_SUB_BJ", "userId", e.userId());
        printField("SET_SUB_BJ", "flag", e.flag());
        printField("SET_SUB_BJ", "hide", e.hide());
        printField("SET_SUB_BJ", "nickname", e.nickname());
        printField("SET_SUB_BJ", "eventType", e.eventType());

        assertEquals(ChatEvent.SET_SUB_BJ, e.eventType());
        assertNotNull(e.userId());
    }

    @Test
    void test_SET_USER_FLAG() {
        String raw = loadFirstPacket("SET_USER_FLAG");
        assumeTrue(raw != null, "SET_USER_FLAG fixture 없음");

        SetUserFlagEvent e = decode(ChatEvent.SET_USER_FLAG, raw);
        assertNotNull(e, "SET_USER_FLAG 이벤트 디코딩 실패");

        printField("SET_USER_FLAG", "oldFlag", e.oldFlag());
        printField("SET_USER_FLAG", "userId", e.userId());
        printField("SET_USER_FLAG", "userNickname", e.userNickname());
        printField("SET_USER_FLAG", "newFlag", e.newFlag());
        printField("SET_USER_FLAG", "eventType", e.eventType());

        assertEquals(ChatEvent.SET_USER_FLAG, e.eventType());
        assertNotNull(e.userId());
    }

    @Test
    void test_TRANSLATION_STATE() {
        String raw = loadFirstPacket("TRANSLATION_STATE");
        assumeTrue(raw != null, "TRANSLATION_STATE fixture 없음");

        TranslationStateEvent e = decode(ChatEvent.TRANSLATION_STATE, raw);
        assertNotNull(e, "TRANSLATION_STATE 이벤트 디코딩 실패");

        printField("TRANSLATION_STATE", "state", e.state());
        printField("TRANSLATION_STATE", "eventType", e.eventType());

        assertEquals(ChatEvent.TRANSLATION_STATE, e.eventType());
    }

    @Test
    void test_VIDEO_BALLOON() {
        String raw = loadFirstPacket("VIDEO_BALLOON");
        assumeTrue(raw != null, "VIDEO_BALLOON fixture 없음");

        VideoBalloonEvent e = decode(ChatEvent.VIDEO_BALLOON, raw);
        assertNotNull(e, "VIDEO_BALLOON 이벤트 디코딩 실패");

        printField("VIDEO_BALLOON", "chatNo", e.chatNo());
        printField("VIDEO_BALLOON", "bjId", e.bjId());
        printField("VIDEO_BALLOON", "userId", e.userId());
        printField("VIDEO_BALLOON", "userNickname", e.userNickname());
        printField("VIDEO_BALLOON", "balloonCount", e.balloonCount());
        printField("VIDEO_BALLOON", "fanOrder", e.fanOrder());
        printField("VIDEO_BALLOON", "isTopFan", e.isTopFan());
        printField("VIDEO_BALLOON", "relay", e.relay());
        printField("VIDEO_BALLOON", "fileName", e.fileName());
        printField("VIDEO_BALLOON", "isDefault", e.isDefault());
        printField("VIDEO_BALLOON", "extraData", e.extraData());
        printField("VIDEO_BALLOON", "eventType", e.eventType());

        assertEquals(ChatEvent.VIDEO_BALLOON, e.eventType());
        assertNotNull(e.bjId());
        assertNotNull(e.userId());
        assertTrue(e.balloonCount() > 0, "풍선 수는 0보다 커야 함");
    }
}
