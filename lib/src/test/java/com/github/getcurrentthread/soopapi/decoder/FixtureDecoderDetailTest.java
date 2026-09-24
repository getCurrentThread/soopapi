package com.github.getcurrentthread.soopapi.decoder;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.github.getcurrentthread.soopapi.code.ChatIceType;
import com.github.getcurrentthread.soopapi.code.UserFlag;
import com.github.getcurrentthread.soopapi.code.UserFlag2;
import com.github.getcurrentthread.soopapi.code.UserLevel;
import com.github.getcurrentthread.soopapi.decoder.factory.DefaultMessageDecoderFactory;
import com.github.getcurrentthread.soopapi.decoder.message.IMessageDecoder;
import com.github.getcurrentthread.soopapi.event.ChatEvent;
import com.github.getcurrentthread.soopapi.event.EventEmitter;
import com.github.getcurrentthread.soopapi.event.EventListener;
import com.github.getcurrentthread.soopapi.event.model.*;

/**
 * 이벤트 타입별 합성 패킷({@link SyntheticPackets})을 실제 디코더 맵으로 디코딩해 모든 필드 값과 파생 접근자를 검증합니다.
 *
 * <p>각 테스트는 패킷을 {@link MessageDispatcher}에 넣어 기대한 타입의 이벤트가 정확히 하나 나오는지 확인한 뒤, 디코더가 읽는 필드를 하나씩
 * {@code assertEquals}로 비교합니다. 패킷은 테스트 코드에 있으므로 항상 실행됩니다.
 */
class FixtureDecoderDetailTest {

    private static final Map<ChatEvent, IMessageDecoder> DECODERS =
            new DefaultMessageDecoderFactory().createDecoders();

    /** 패킷을 디스패치해 {@code eventType} 이벤트가 정확히 하나 나오는지 확인하고, 공통 필드를 검증한 뒤 반환합니다. */
    private static <T extends BaseEvent> T decode(
            String rawPacket, ChatEvent eventType, Class<T> type) {
        EventEmitter emitter = new EventEmitter();
        MessageDispatcher dispatcher = new MessageDispatcher(DECODERS, Runnable::run, emitter);

        List<BaseEvent> received = new ArrayList<>();
        emitter.on(eventType, (EventListener<BaseEvent>) received::add);
        dispatcher.dispatchMessage(rawPacket);

        assertEquals(1, received.size(), eventType + " 이벤트가 정확히 하나 발행되어야 함");
        T event = assertInstanceOf(type, received.get(0));
        assertEquals(eventType, event.eventType());
        assertEquals(rawPacket, event.raw());
        assertTrue(event.timestamp() > 0, "timestamp가 0 이하");
        return event;
    }

    @Test
    void adconEffect_decodesAllFields() {
        AdconEffectEvent e =
                decode(
                        SyntheticPackets.ADCON_EFFECT,
                        ChatEvent.ADCON_EFFECT,
                        AdconEffectEvent.class);

        assertEquals(1000, e.chatNo());
        assertEquals("bj1", e.bjId());
        assertEquals("user1", e.senderId());
        assertEquals("닉네임1", e.senderNickname());
        assertEquals("hello", e.message());
        assertEquals("hello2", e.message2());
        assertEquals("title1", e.title());
        assertEquals("https://example.com/adcon/img1.png", e.urlImg());
        assertEquals("https://example.com/adcon/default1.png", e.urlDefault());
        assertEquals(200, e.adconCount());
        assertEquals(5, e.fanOrder());
        assertEquals(1, e.isTopFan());
        assertEquals(2, e.isFanChief());
        assertEquals(3, e.isSubRoom());
    }

    @Test
    void banWord_splitsListAndDropsEmptyTokens() {
        BanWordEvent e = decode(SyntheticPackets.BAN_WORD, ChatEvent.BAN_WORD, BanWordEvent.class);

        assertEquals("**", e.replaceWord());
        assertEquals(List.of("금지어1", "금지어2"), e.banWordList());
    }

    @Test
    void bjNotice_decodesAllFields() {
        BjNoticeEvent e =
                decode(SyntheticPackets.BJ_NOTICE, ChatEvent.BJ_NOTICE, BjNoticeEvent.class);

        assertEquals(1, e.show());
        assertEquals("공지1", e.message());
    }

    @Test
    void bjStickerItem_decodesType() {
        BjStickerItemEvent e =
                decode(
                        SyntheticPackets.BJ_STICKER_ITEM,
                        ChatEvent.BJ_STICKER_ITEM,
                        BjStickerItemEvent.class);

        assertEquals(4, e.type());
    }

    @Test
    void chatMessage_decodesAllFieldsAndSenderLevel() {
        ChatMessageEvent e =
                decode(
                        SyntheticPackets.CHAT_MESSAGE,
                        ChatEvent.CHAT_MESSAGE,
                        ChatMessageEvent.class);

        assertEquals("hello", e.message());
        assertEquals("user1", e.senderId());
        assertEquals(5, e.type());
        assertEquals(3, e.chatLang());
        assertEquals("닉네임1", e.senderNickname());
        assertEquals("81952|32768", e.senderFlag());
        assertEquals("-1", e.subscriptionMonth());
        assertEquals("#FF0000", e.randomNicknameColor());
        assertEquals("#00FF00", e.randomNicknameColorDarkmode());
        assertEquals(
                new UserLevel(
                        Set.of(UserFlag.FANCLUB, UserFlag.MOBILE, UserFlag.REALNAME),
                        Set.of(UserFlag2.SPECIFY)),
                e.senderLevel());
    }

    @Test
    void chatUser_decodesEntriesAndLevels() {
        ChatUserEvent e =
                decode(SyntheticPackets.CHAT_USER, ChatEvent.CHAT_USER, ChatUserEvent.class);

        assertEquals(1, e.type());
        assertEquals(
                List.of(
                        new ChatUserEvent.ChatUserEntry("viewer1", "닉네임1", "16|16384"),
                        new ChatUserEvent.ChatUserEntry("viewer2", "닉네임2", "81952|32768")),
                e.userList());
        assertEquals(
                new UserLevel(Set.of(UserFlag.GUEST), Set.of(UserFlag2.PC)),
                e.userList().get(0).level());
        assertEquals(
                new UserLevel(
                        Set.of(UserFlag.FANCLUB, UserFlag.MOBILE, UserFlag.REALNAME),
                        Set.of(UserFlag2.SPECIFY)),
                e.userList().get(1).level());
    }

    @Test
    void chuserExtend_decodesStatusPerUser() {
        ChuserExtendEvent e =
                decode(
                        SyntheticPackets.CHUSER_EXTEND,
                        ChatEvent.CHUSER_EXTEND,
                        ChuserExtendEvent.class);

        assertEquals(
                Map.of(
                        "viewer1", Map.of("fw", 1, "afw", 1),
                        "viewer2", Map.of("fw", 3, "afw", 0)),
                e.userStatus());
    }

    @Test
    void emoticonTicket_decodesValue() {
        EmoticonTicketEvent e =
                decode(
                        SyntheticPackets.EMOTICON_TICKET,
                        ChatEvent.EMOTICON_TICKET,
                        EmoticonTicketEvent.class);

        assertEquals(1, e.value());
    }

    @Test
    void followItem_decodesAllFields() {
        FollowItemEvent e =
                decode(SyntheticPackets.FOLLOW_ITEM, ChatEvent.FOLLOW_ITEM, FollowItemEvent.class);

        assertEquals(1000, e.chatNo());
        assertEquals("bj1", e.recvId());
        assertEquals("user1", e.sendId());
        assertEquals("닉네임1", e.sendNick());
        assertEquals(111, e.type());
    }

    @Test
    void followItemEffect_decodesAllFields() {
        FollowItemEffectEvent e =
                decode(
                        SyntheticPackets.FOLLOW_ITEM_EFFECT,
                        ChatEvent.FOLLOW_ITEM_EFFECT,
                        FollowItemEffectEvent.class);

        assertEquals("bj1", e.bjId());
        assertEquals("user1", e.sendId());
        assertEquals("닉네임1", e.sendNick());
        assertEquals(27, e.month());
        assertEquals(1000, e.chatNo());
    }

    @Test
    void iceMode_decodesLegacyValue() {
        IceModeEvent e = decode(SyntheticPackets.ICE_MODE, ChatEvent.ICE_MODE, IceModeEvent.class);

        assertEquals(1, e.iceMode());
        assertEquals(ChatIceType.NORMAL, e.iceType());
        assertFalse(e.isIceFlagMode());
        assertEquals(Set.of(), e.iceFlags());
    }

    @Test
    void iceModeEx_decodesAllFieldsAndFreezeFlags() {
        IceModeExEvent e =
                decode(SyntheticPackets.ICE_MODE_EX, ChatEvent.ICE_MODE_EX, IceModeExEvent.class);

        assertEquals(1, e.iceMode());
        assertEquals(1008, e.freezeType());
        assertEquals(10, e.balloonLimitCount());
        assertEquals(3, e.subscriptionLimitCount());
        assertEquals(ChatIceType.NORMAL, e.iceType());
        assertFalse(e.isIceFlagMode());
        assertEquals(Set.of(), e.iceFlags());
        // 매핑되지 않은 512 비트는 무시된다
        assertEquals(
                EnumSet.of(
                        ChatIceType.Flag.NORMAL2,
                        ChatIceType.Flag.FAN2,
                        ChatIceType.Flag.SUP2,
                        ChatIceType.Flag.TOP_FAN2,
                        ChatIceType.Flag.FOLLOWER2),
                e.freezeFlags());
    }

    @Test
    void itemDrops_decodesAllFields() {
        ItemDropsEvent e =
                decode(SyntheticPackets.ITEM_DROPS, ChatEvent.ITEM_DROPS, ItemDropsEvent.class);

        assertEquals("bj1", e.bjId());
        assertEquals("상품1", e.dropsName());
        assertEquals("hello", e.dropsMsg());
        assertEquals("https://example.com/drops/item1.png", e.dropsImgUrl());
    }

    @Test
    void joinChannel_decodesAllFieldsAndUserLevel() {
        JoinChannelEvent e =
                decode(
                        SyntheticPackets.JOIN_CHANNEL,
                        ChatEvent.JOIN_CHANNEL,
                        JoinChannelEvent.class);

        assertEquals("1000", e.chatNo());
        assertEquals("bj1", e.bjId());
        assertEquals(10, e.maxSubBjCount());
        assertEquals("패밀리1", e.familyNickname());
        assertEquals("16|16384", e.userFlag());
        assertEquals(new UserLevel(Set.of(UserFlag.GUEST), Set.of(UserFlag2.PC)), e.userLevel());
    }

    @Test
    void kickMsgState_decodesAllFields() {
        KickMsgStateEvent e =
                decode(
                        SyntheticPackets.KICK_MSG_STATE,
                        ChatEvent.KICK_MSG_STATE,
                        KickMsgStateEvent.class);

        assertEquals("1000", e.chatNo());
        assertTrue(e.isHideKickMessage());
    }

    @Test
    void login_decodesAllFieldsAndUserLevel() {
        LoginEvent e = decode(SyntheticPackets.LOGIN, ChatEvent.LOGIN, LoginEvent.class);

        assertEquals("viewer1", e.userId());
        assertEquals("16|0", e.userFlag());
        assertEquals(new UserLevel(Set.of(UserFlag.GUEST), Set.of()), e.userLevel());
    }

    @Test
    void mission_decodesJsonPayload() {
        MissionEvent e = decode(SyntheticPackets.MISSION, ChatEvent.MISSION, MissionEvent.class);

        assertEquals(
                Map.of(
                        "type", "CHALLENGE_NOTICE",
                        "mission_status", "FAIL",
                        "title", "미션1",
                        "key", 1),
                e.data());
    }

    @Test
    void missionSettle_decodesJsonPayload() {
        MissionSettleEvent e =
                decode(
                        SyntheticPackets.MISSION_SETTLE,
                        ChatEvent.MISSION_SETTLE,
                        MissionSettleEvent.class);

        assertEquals(
                Map.of("chno", 1000, "list", List.of(List.of("user1", "닉네임1", 100))), e.data());
    }

    @Test
    void ogqEmoticon_decodesAllFieldsAndSenderAccessors() {
        OGQEmoticonEvent e =
                decode(
                        SyntheticPackets.OGQ_EMOTICON,
                        ChatEvent.OGQ_EMOTICON,
                        OGQEmoticonEvent.class);

        assertEquals("1000", e.chatNo());
        assertEquals("hello", e.message());
        assertEquals("ogqgroup1", e.groupId());
        assertEquals("3", e.subId());
        assertEquals("1", e.version());
        assertEquals("user1", e.userInfo());
        assertEquals("닉네임1", e.color());
        assertEquals("2", e.chatLang());
        assertEquals("4", e.type());
        assertEquals("user1", e.senderId());
        assertEquals("닉네임1", e.senderNickname());
    }

    @Test
    void ogqEmoticonGift_decodesAllFields() {
        GiftOGQEmoticonEvent e =
                decode(
                        SyntheticPackets.OGQ_EMOTICON_GIFT,
                        ChatEvent.OGQ_EMOTICON_GIFT,
                        GiftOGQEmoticonEvent.class);

        assertEquals("user1", e.senderId());
        assertEquals("닉네임1", e.senderNick());
        assertEquals("bj1", e.receivedId());
        assertEquals("닉네임2", e.receivedNick());
        assertEquals("스티커1", e.ogqTitle());
        assertEquals("https://example.com/ogq/sticker1.png", e.ogqImageUrl());
    }

    @Test
    void sendBalloon_decodesAllFields() {
        SendBalloonEvent e =
                decode(
                        SyntheticPackets.SEND_BALLOON,
                        ChatEvent.SEND_BALLOON,
                        SendBalloonEvent.class);

        assertEquals("bj1", e.bjId());
        assertEquals("user1", e.senderId());
        assertEquals("닉네임1", e.senderNickname());
        assertEquals(100, e.count());
        assertEquals(2, e.fanOrder());
        assertEquals("balloon1", e.fileName());
        assertTrue(e.isDefault());
        assertEquals(3, e.isTopFan());
        assertEquals("tts1", e.ttsData());
    }

    @Test
    void sendQuickView_decodesAllFields() {
        QuickViewEvent e =
                decode(
                        SyntheticPackets.SEND_QUICK_VIEW,
                        ChatEvent.SEND_QUICK_VIEW,
                        QuickViewEvent.class);

        assertEquals("user1", e.senderId());
        assertEquals("닉네임1", e.senderNickname());
        assertEquals("viewer1", e.receiverId());
        assertEquals("닉네임2", e.receiverNickname());
        assertEquals(101, e.itemType());
    }

    @Test
    void sendSubscription_decodesAllFields() {
        SendSubscriptionEvent e =
                decode(
                        SyntheticPackets.SEND_SUBSCRIPTION,
                        ChatEvent.SEND_SUBSCRIPTION,
                        SendSubscriptionEvent.class);

        assertEquals("user1", e.senderId());
        assertEquals("닉네임1", e.senderNickname());
        assertEquals("viewer1", e.receiverId());
        assertEquals("닉네임2", e.receiverNickname());
        assertEquals("bj1", e.subscriptionId());
        assertEquals("닉네임3", e.subscriptionNickname());
        assertEquals(11, e.itemType());
        assertEquals("code1", e.itemCode());
        assertEquals(1, e.isSubscription());
        assertEquals("0", e.subscriptionType());
        assertEquals("3", e.subscriptionPeriod());
        assertEquals(30, e.subscriptionRemain());
        assertEquals(2, e.subscriptionPaycount());
    }

    @Test
    void setBjStat_carriesCommonFieldsOnly() {
        // 디코더가 읽는 필드가 없으므로 decode()의 eventType/raw/timestamp 검증이 전부다
        decode(SyntheticPackets.SET_BJ_STAT, ChatEvent.SET_BJ_STAT, SetBjStatEvent.class);
    }

    @Test
    void setDumb_decodesAllFields() {
        SetDumbEvent e = decode(SyntheticPackets.SET_DUMB, ChatEvent.SET_DUMB, SetDumbEvent.class);

        assertEquals("user1", e.userId());
        assertEquals("info1", e.userInfo());
        assertEquals(30, e.dumbTime());
        assertEquals(1, e.dumbCount());
        assertEquals("bj1", e.adminId());
        assertEquals(2, e.adminType());
        assertEquals("extra1", e.extraInfo());
        assertEquals("닉네임1", e.userNickname());
    }

    @Test
    void setNickname_decodesAllFieldsAndLevel() {
        SetNicknameEvent e =
                decode(
                        SyntheticPackets.SET_NICKNAME,
                        ChatEvent.SET_NICKNAME,
                        SetNicknameEvent.class);

        assertEquals("user1", e.userId());
        assertEquals("닉네임2", e.newNickname());
        assertEquals(1, e.changeType());
        assertEquals("65536|16384", e.flag());
        assertEquals("닉네임1", e.oldNickname());
        assertEquals(new UserLevel(Set.of(UserFlag.REALNAME), Set.of(UserFlag2.PC)), e.level());
    }

    @Test
    void setSubBj_decodesAllFieldsAndLevel() {
        SetSubBjEvent e =
                decode(SyntheticPackets.SET_SUB_BJ, ChatEvent.SET_SUB_BJ, SetSubBjEvent.class);

        assertEquals("user1", e.userId());
        assertEquals("4|16384", e.flag());
        assertEquals(1, e.hide());
        assertEquals("닉네임1", e.nickname());
        assertEquals(new UserLevel(Set.of(UserFlag.BJ), Set.of(UserFlag2.PC)), e.level());
    }

    @Test
    void setUserFlag_decodesAllFieldsAndLevels() {
        SetUserFlagEvent e =
                decode(
                        SyntheticPackets.SET_USER_FLAG,
                        ChatEvent.SET_USER_FLAG,
                        SetUserFlagEvent.class);

        assertEquals("16|16384", e.oldFlag());
        assertEquals("user1", e.userId());
        assertEquals("닉네임1", e.userNickname());
        assertEquals("48|16384", e.newFlag());
        assertEquals(new UserLevel(Set.of(UserFlag.GUEST), Set.of(UserFlag2.PC)), e.oldLevel());
        assertEquals(
                new UserLevel(Set.of(UserFlag.GUEST, UserFlag.FANCLUB), Set.of(UserFlag2.PC)),
                e.newLevel());
    }

    @Test
    void translationState_decodesState() {
        TranslationStateEvent e =
                decode(
                        SyntheticPackets.TRANSLATION_STATE,
                        ChatEvent.TRANSLATION_STATE,
                        TranslationStateEvent.class);

        assertEquals(1, e.state());
    }

    @Test
    void videoBalloon_decodesAllFields() {
        VideoBalloonEvent e =
                decode(
                        SyntheticPackets.VIDEO_BALLOON,
                        ChatEvent.VIDEO_BALLOON,
                        VideoBalloonEvent.class);

        assertEquals("1000", e.chatNo());
        assertEquals("bj1", e.bjId());
        assertEquals("user1", e.userId());
        assertEquals("닉네임1", e.userNickname());
        assertEquals(50, e.balloonCount());
        assertEquals(3, e.fanOrder());
        assertEquals(4, e.isTopFan());
        assertEquals("2", e.relay());
        assertEquals("video1", e.fileName());
        assertTrue(e.isDefault());
        assertEquals("00112233445566778899aabbccddeeff", e.extraData());
    }
}
