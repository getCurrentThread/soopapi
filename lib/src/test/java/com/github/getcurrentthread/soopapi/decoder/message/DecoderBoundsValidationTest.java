package com.github.getcurrentthread.soopapi.decoder.message;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import org.junit.jupiter.api.Test;

import com.github.getcurrentthread.soopapi.event.ChatEvent;
import com.github.getcurrentthread.soopapi.event.model.AdInBroadJsonEvent;
import com.github.getcurrentthread.soopapi.event.model.AdminChatUserEvent;
import com.github.getcurrentthread.soopapi.event.model.BanWordEvent;
import com.github.getcurrentthread.soopapi.event.model.BaseEvent;
import com.github.getcurrentthread.soopapi.event.model.ChatMessageEvent;
import com.github.getcurrentthread.soopapi.event.model.ChatUserEvent;
import com.github.getcurrentthread.soopapi.event.model.ChuserExtendEvent;
import com.github.getcurrentthread.soopapi.event.model.KickUserListEvent;
import com.github.getcurrentthread.soopapi.event.model.MissionEvent;
import com.github.getcurrentthread.soopapi.event.model.MissionSettleEvent;
import com.github.getcurrentthread.soopapi.event.model.OGQEmoticonEvent;
import com.github.getcurrentthread.soopapi.event.model.SendBalloonEvent;
import com.github.getcurrentthread.soopapi.event.model.StationAdconEvent;

class DecoderBoundsValidationTest {

    // --- SendBalloonDecoder (최소 요소 수=10) ---

    @Test
    void sendBalloon_validFullMessage_returnsEvent() {
        SendBalloonDecoder decoder = new SendBalloonDecoder();
        String[] parts = {
            "bjid", "sender", "nick", "5", "1", "", "", "balloon.swf", "1", "0", "tts"
        };

        BaseEvent result = decoder.decode(parts, "raw");

        assertNotNull(result);
        assertInstanceOf(SendBalloonEvent.class, result);
        SendBalloonEvent event = (SendBalloonEvent) result;
        assertEquals("bjid", event.bjId());
        assertEquals("sender", event.senderId());
        assertEquals("nick", event.senderNickname());
        assertEquals(5, event.count());
        assertEquals(1, event.fanOrder());
        assertEquals("balloon.swf", event.fileName());
        assertTrue(event.isDefault());
        assertEquals(0, event.isTopFan());
        assertEquals("tts", event.ttsData());
    }

    @Test
    void sendBalloon_tooFewParts_returnsNull() {
        SendBalloonDecoder decoder = new SendBalloonDecoder();
        String[] parts = {"bjid", "sender", "nick", "5", "1", "", "", "balloon.swf", "1"};

        BaseEvent result = decoder.decode(parts, "raw");

        assertNull(result, "Should return null when parts.length < 10");
    }

    @Test
    void sendBalloon_emptyParts_returnsNull() {
        SendBalloonDecoder decoder = new SendBalloonDecoder();

        BaseEvent result = decoder.decode(new String[0], "raw");

        assertNull(result, "Should return null for empty parts array");
    }

    @Test
    void sendBalloon_invalidIntegerInCount_doesNotThrow() {
        SendBalloonDecoder decoder = new SendBalloonDecoder();
        String[] parts = {
            "bjid", "sender", "nick", "abc", "1", "", "", "balloon.swf", "1", "0", "tts"
        };

        BaseEvent result = assertDoesNotThrow(() -> decoder.decode(parts, "raw"));

        assertNotNull(result);
        SendBalloonEvent event = (SendBalloonEvent) result;
        assertEquals(0, event.count(), "Invalid integer should fall back to default 0");
    }

    // --- MissionDecoder (최소 요소 수=1, GsonUtil.fromJson 사용) ---

    @Test
    void mission_validJson_returnsEvent() {
        MissionDecoder decoder = new MissionDecoder();
        String[] parts = {"{\"type\":\"mission\",\"amount\":100}"};

        BaseEvent result = decoder.decode(parts, "raw");

        assertNotNull(result);
        assertInstanceOf(MissionEvent.class, result);
    }

    @Test
    void mission_emptyParts_returnsNull() {
        MissionDecoder decoder = new MissionDecoder();

        BaseEvent result = decoder.decode(new String[0], "raw");

        assertNull(result, "Should return null for empty parts array");
    }

    @Test
    void mission_invalidJson_returnsNull() {
        MissionDecoder decoder = new MissionDecoder();
        String[] parts = {"not valid json!!"};

        BaseEvent result = decoder.decode(parts, "raw");

        assertNull(result, "Invalid JSON should return null instead of throwing");
    }

    @Test
    void mission_nullJson_returnsNull() {
        MissionDecoder decoder = new MissionDecoder();
        String[] parts = {"null"};

        BaseEvent result = decoder.decode(parts, "raw");

        assertNull(result, "JSON literal 'null' should result in null return");
    }

    // --- AdInBroadJsonDecoder (최소 요소 수=1, GsonUtil.fromJson 사용) ---

    @Test
    void adInBroadJson_validJson_returnsEvent() {
        AdInBroadJsonDecoder decoder = new AdInBroadJsonDecoder();
        String[] parts = {"{\"ad\":\"data\",\"id\":1}"};

        BaseEvent result = decoder.decode(parts, "raw");

        assertNotNull(result);
        assertInstanceOf(AdInBroadJsonEvent.class, result);
    }

    @Test
    void adInBroadJson_emptyParts_returnsNull() {
        AdInBroadJsonDecoder decoder = new AdInBroadJsonDecoder();

        BaseEvent result = decoder.decode(new String[0], "raw");

        assertNull(result, "Should return null for empty parts array");
    }

    @Test
    void adInBroadJson_invalidJson_returnsNull() {
        AdInBroadJsonDecoder decoder = new AdInBroadJsonDecoder();
        String[] parts = {"{{bad json}}"};

        BaseEvent result = decoder.decode(parts, "raw");

        assertNull(result, "Invalid JSON should return null instead of throwing");
    }

    // --- MissionSettleDecoder (JsonPayloadDecoder 공통 경로) ---

    @Test
    void missionSettle_validJson_returnsEvent() {
        MissionSettleDecoder decoder = new MissionSettleDecoder();
        String[] parts = {"{\"type\":\"settle\",\"amount\":100}"};

        BaseEvent result = decoder.decode(parts, "raw");

        MissionSettleEvent event = assertInstanceOf(MissionSettleEvent.class, result);
        assertEquals(ChatEvent.MISSION_SETTLE, event.eventType());
        assertEquals("settle", event.data().get("type"));
        assertEquals(100, event.data().get("amount"));
    }

    @Test
    void jsonDecoders_nonObjectRoot_returnNullAndLogAtFine() {
        Logger logger = Logger.getLogger(JsonPayloadDecoder.class.getName());
        List<LogRecord> records = new ArrayList<>();
        Handler handler =
                new Handler() {
                    @Override
                    public void publish(LogRecord record) {
                        records.add(record);
                    }

                    @Override
                    public void flush() {}

                    @Override
                    public void close() {}
                };
        handler.setLevel(Level.ALL);
        Level previousLevel = logger.getLevel();
        logger.setLevel(Level.FINE);
        logger.addHandler(handler);
        try {
            String[] parts = {"[1,2]"};

            assertNull(new MissionDecoder().decode(parts, "raw"));
            assertNull(new MissionSettleDecoder().decode(parts, "raw"));
            assertNull(new AdInBroadJsonDecoder().decode(parts, "raw"));

            assertEquals(3, records.size());
            for (LogRecord record : records) {
                assertEquals(Level.FINE, record.getLevel());
                assertTrue(record.getMessage().contains("[1,2]"), record.getMessage());
            }
        } finally {
            logger.removeHandler(handler);
            logger.setLevel(previousLevel);
        }
    }

    // --- StationAdconDecoder (최소 요소 수=7) ---

    @Test
    void stationAdcon_validMessage_returnsEvent() {
        StationAdconDecoder decoder = new StationAdconDecoder();
        String[] parts = {"bj1", "user1", "nick1", "5", "1", "msg1", "10"};

        BaseEvent result = decoder.decode(parts, "raw");

        StationAdconEvent event = assertInstanceOf(StationAdconEvent.class, result);
        assertEquals("bj1", event.bjId());
        assertEquals("user1", event.userId());
        assertEquals("nick1", event.userNickName());
        assertEquals(5, event.adconCount());
        assertEquals("1", event.isDefault());
        assertEquals("msg1", event.adconMsg());
        assertEquals("10", event.chatNumber());
    }

    @Test
    void stationAdcon_tooFewParts_returnsNull() {
        StationAdconDecoder decoder = new StationAdconDecoder();
        String[] parts = {"bj1", "user1", "nick1", "5", "1", "msg1"};

        assertNull(decoder.decode(parts, "raw"), "Should return null when parts.length < 7");
        assertNull(decoder.decode(new String[0], "raw"), "Should return null for empty parts");
    }

    // --- OGQEmoticonDecoder (최소 요소 수=6, 선택 필드 6~8) ---

    @Test
    void ogqEmoticon_fullMessage_exposesSenderAliases() {
        OGQEmoticonDecoder decoder = new OGQEmoticonDecoder();
        String[] parts = {"1", "msg1", "grp1", "3", "1", "user1", "nick1", "2", "4", ""};

        BaseEvent result = decoder.decode(parts, "raw");

        OGQEmoticonEvent event = assertInstanceOf(OGQEmoticonEvent.class, result);
        assertEquals("1", event.chatNo());
        assertEquals("grp1", event.groupId());
        assertEquals("user1", event.senderId());
        assertEquals("user1", event.userInfo());
        assertEquals("nick1", event.senderNickname());
        assertEquals("nick1", event.color());
        assertEquals("2", event.chatLang());
        assertEquals("4", event.type());
    }

    @Test
    void ogqEmoticon_withoutTrailingOptionalFields_keepsNickname() {
        OGQEmoticonDecoder decoder = new OGQEmoticonDecoder();
        String[] parts = {"1", "msg1", "grp1", "3", "1", "user1", "nick1", ""};

        OGQEmoticonEvent event =
                assertInstanceOf(OGQEmoticonEvent.class, decoder.decode(parts, "raw"));

        assertEquals("nick1", event.senderNickname());
        assertEquals("", event.chatLang());
        assertEquals("", event.type());
    }

    @Test
    void ogqEmoticon_minimumParts_leavesOptionalFieldsEmpty() {
        OGQEmoticonDecoder decoder = new OGQEmoticonDecoder();
        String[] parts = {"1", "msg1", "grp1", "3", "1", "user1"};

        OGQEmoticonEvent event =
                assertInstanceOf(OGQEmoticonEvent.class, decoder.decode(parts, "raw"));

        assertEquals("user1", event.senderId());
        assertEquals("", event.senderNickname());
    }

    @Test
    void ogqEmoticon_tooFewParts_returnsNull() {
        OGQEmoticonDecoder decoder = new OGQEmoticonDecoder();

        assertNull(decoder.decode(new String[] {"1", "msg1", "grp1", "3", "1"}, "raw"));
        assertNull(decoder.decode(new String[] {""}, "raw"));
        assertNull(decoder.decode(new String[0], "raw"));
    }

    // --- BanWordDecoder ---

    @Test
    void banWord_emptyField_returnsEmptyList() {
        BanWordDecoder decoder = new BanWordDecoder();

        BanWordEvent event =
                assertInstanceOf(
                        BanWordEvent.class, decoder.decode(new String[] {"***", "", ""}, "raw"));

        assertEquals("***", event.replaceWord());
        assertEquals(List.of(), event.banWordList());
    }

    @Test
    void banWord_missingField_returnsEmptyList() {
        BanWordDecoder decoder = new BanWordDecoder();

        BanWordEvent event =
                assertInstanceOf(BanWordEvent.class, decoder.decode(new String[] {"***"}, "raw"));

        assertEquals(List.of(), event.banWordList());
    }

    @Test
    void banWord_dropsEmptyTokensWithoutTrimming() {
        BanWordDecoder decoder = new BanWordDecoder();

        BanWordEvent event =
                assertInstanceOf(
                        BanWordEvent.class,
                        decoder.decode(new String[] {"***", "word1,,word2, word3,", ""}, "raw"));

        assertEquals(List.of("word1", "word2", " word3"), event.banWordList());
    }

    @Test
    void banWord_listIsUnmodifiable() {
        BanWordDecoder decoder = new BanWordDecoder();

        BanWordEvent event =
                assertInstanceOf(
                        BanWordEvent.class,
                        decoder.decode(new String[] {"***", "word1,word2"}, "raw"));

        assertThrows(UnsupportedOperationException.class, () -> event.banWordList().add("word3"));
        assertThrows(UnsupportedOperationException.class, () -> event.banWordList().clear());
    }

    @Test
    void banWord_constructorCopiesTheList() {
        List<String> source = new ArrayList<>(List.of("word1"));

        BanWordEvent event = new BanWordEvent("***", source, ChatEvent.BAN_WORD, "raw", 0);
        source.add("word2");

        assertEquals(List.of("word1"), event.banWordList(), "Later changes must not leak in");
    }

    // --- 목록 컴포넌트 (ChatUser / AdminChatUser / KickUserList / BanWord) ---

    @Test
    void listDecoders_returnUnmodifiableLists() {
        ChatUserEvent chatUser =
                assertInstanceOf(
                        ChatUserEvent.class,
                        new ChatUserDecoder()
                                .decode(new String[] {"1", "user1", "닉네임1", "0"}, "raw"));
        AdminChatUserEvent adminChatUser =
                assertInstanceOf(
                        AdminChatUserEvent.class,
                        new AdminChatUserDecoder()
                                .decode(new String[] {"1", "user1", "닉네임1", "0"}, "raw"));
        KickUserListEvent kickUserList =
                assertInstanceOf(
                        KickUserListEvent.class,
                        new KickUserListDecoder()
                                .decode(
                                        new String[] {"user1", "닉네임1", "1000", "bj1", "닉네임2", "0"},
                                        "raw"));

        assertEquals(1, chatUser.userList().size());
        assertEquals(1, adminChatUser.users().size());
        assertEquals(1, kickUserList.kickedUsers().size());
        assertThrows(UnsupportedOperationException.class, () -> chatUser.userList().clear());
        assertThrows(UnsupportedOperationException.class, () -> adminChatUser.users().clear());
        assertThrows(UnsupportedOperationException.class, () -> kickUserList.kickedUsers().clear());
    }

    @Test
    void listRecords_mapNullToEmptyList() {
        assertEquals(
                List.of(), new ChatUserEvent(0, null, ChatEvent.CHAT_USER, "raw", 0).userList());
        assertEquals(
                List.of(),
                new AdminChatUserEvent("list", null, ChatEvent.ADMIN_CHAT_USER, "raw", 0).users());
        assertEquals(
                List.of(),
                new KickUserListEvent(null, ChatEvent.KICK_USERLIST, "raw", 0).kickedUsers());
        assertEquals(
                List.of(),
                new BanWordEvent("***", null, ChatEvent.BAN_WORD, "raw", 0).banWordList());
    }

    // --- ChuserExtendEvent (중첩 맵 컴포넌트) ---

    @Test
    void chuserExtend_mapsAreUnmodifiable() {
        ChuserExtendEvent event =
                assertInstanceOf(
                        ChuserExtendEvent.class,
                        new ChuserExtendDecoder()
                                .decode(new String[] {"1000", "viewer1", "fw=1&afw=0"}, "raw"));

        assertEquals(Map.of("viewer1", Map.of("fw", 1, "afw", 0)), event.userStatus());
        assertThrows(UnsupportedOperationException.class, () -> event.userStatus().clear());
        assertThrows(
                UnsupportedOperationException.class,
                () -> event.userStatus().get("viewer1").put("fw", 0));
    }

    @Test
    void chuserExtend_constructorCopiesMapsAndMapsNullToEmpty() {
        Map<String, Integer> inner = new HashMap<>(Map.of("fw", 1));
        Map<String, Map<String, Integer>> outer = new HashMap<>(Map.of("viewer1", inner));

        ChuserExtendEvent event = new ChuserExtendEvent(outer, ChatEvent.CHUSER_EXTEND, "raw", 0);
        inner.put("afw", 1);
        outer.put("viewer2", Map.of());

        assertEquals(Map.of("viewer1", Map.of("fw", 1)), event.userStatus());
        assertEquals(
                Map.of(),
                new ChuserExtendEvent(null, ChatEvent.CHUSER_EXTEND, "raw", 0).userStatus());
    }

    // --- ChatMessageDecoder (최소 요소 수=8, safeParseInt 교차 검증) ---

    @Test
    void chatMessage_invalidIntegersInTypeAndChatLang_doesNotThrow() {
        ChatMessageDecoder decoder = new ChatMessageDecoder();
        String[] parts = {"msg", "user", "skip", "not_int", "also_bad", "nick", "0", "0"};

        BaseEvent result = assertDoesNotThrow(() -> decoder.decode(parts, "raw"));

        assertNotNull(result);
        ChatMessageEvent event = (ChatMessageEvent) result;
        assertEquals(0, event.type(), "Invalid type should fall back to default 0");
        assertEquals(0, event.chatLang(), "Invalid chatLang should fall back to default 0");
    }
}
