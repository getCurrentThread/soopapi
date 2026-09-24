package com.github.getcurrentthread.soopapi.decoder;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;

import com.github.getcurrentthread.soopapi.constant.SOOPConstants;
import com.github.getcurrentthread.soopapi.event.ChatEvent;
import com.github.getcurrentthread.soopapi.event.model.*;

/**
 * 서버 형식을 그대로 따르는 이벤트 타입별 합성 raw 패킷.
 *
 * <p>패킷은 {@code ESC + 서비스 코드(4자리) + payload UTF-8 바이트 길이(6자리, 0 패딩) + "00" + payload}입니다. payload는
 * {@link SOOPConstants#F}로 시작하고 각 필드 뒤에 F가 붙습니다. 필드 순서와 개수는 각 디코더가 읽는 위치를 따르며, 디코더가 읽지 않는 자리는
 * {@code "0"}으로 채웁니다. 각 상수 위 주석이 필드 순서이고 {@code -}는 디코더가 읽지 않는 자리입니다.
 *
 * <p>디코더가 읽는 자리의 값은 같은 패킷의 다른 자리와 겹치지 않게 고릅니다. 정수 필드는 {@code "0"}도 피합니다(채움 값이자 파싱 실패 시 기본값). 그래야
 * 디코더가 위치를 잘못 읽으면 상세 테스트가 실패합니다.
 *
 * <p>ID·닉네임·메시지·채팅방 번호·URL은 모두 합성 placeholder입니다. 실방송 패킷은 DataCollector({@code collectFixtures}
 * 태스크)로 로컬에서만 수집하고, 새 형식을 확인하면 합성 값으로 바꿔 여기에 옮겨 적습니다.
 */
final class SyntheticPackets {

    /** 패킷 하나와 디코딩 결과로 기대하는 이벤트 타입·Record 클래스. */
    record Sample(ChatEvent event, Class<? extends BaseEvent> type, String raw) {}

    // chatNo | bjId | senderId | senderNickname | message | message2 | title | urlImg |
    // urlDefault | adconCount | fanOrder | isTopFan | isFanChief | isSubRoom
    static final String ADCON_EFFECT =
            packet(
                    ChatEvent.ADCON_EFFECT,
                    "1000",
                    "bj1",
                    "user1",
                    "닉네임1",
                    "hello",
                    "hello2",
                    "title1",
                    "https://example.com/adcon/img1.png",
                    "https://example.com/adcon/default1.png",
                    "200",
                    "5",
                    "1",
                    "2",
                    "3");

    // replaceWord | 쉼표로 구분한 금지어 목록(빈 토큰은 버려진다)
    static final String BAN_WORD = packet(ChatEvent.BAN_WORD, "**", "금지어1,,금지어2");

    // - | show | - | message
    static final String BJ_NOTICE = packet(ChatEvent.BJ_NOTICE, "1000", "1", "0", "공지1");

    // type
    static final String BJ_STICKER_ITEM = packet(ChatEvent.BJ_STICKER_ITEM, "4");

    // message | senderId | - | type | chatLang | senderNickname | senderFlag | subscriptionMonth |
    // randomNicknameColor | randomNicknameColorDarkmode
    static final String CHAT_MESSAGE =
            packet(
                    ChatEvent.CHAT_MESSAGE,
                    "hello",
                    "user1",
                    "0",
                    "5",
                    "3",
                    "닉네임1",
                    "81952|32768",
                    "-1",
                    "#FF0000",
                    "#00FF00");

    // type | (id | nickname | flag) 반복
    static final String CHAT_USER =
            packet(
                    ChatEvent.CHAT_USER,
                    "1",
                    "viewer1",
                    "닉네임1",
                    "16|16384",
                    "viewer2",
                    "닉네임2",
                    "81952|32768");

    // - | (userId | "key=value&key=value") 반복
    static final String CHUSER_EXTEND =
            packet(
                    ChatEvent.CHUSER_EXTEND,
                    "1000",
                    "viewer1",
                    "fw=1&afw=1",
                    "viewer2",
                    "fw=3&afw=0");

    // value
    static final String EMOTICON_TICKET = packet(ChatEvent.EMOTICON_TICKET, "1");

    // chatNo | recvId | sendId | sendNick | type
    static final String FOLLOW_ITEM =
            packet(ChatEvent.FOLLOW_ITEM, "1000", "bj1", "user1", "닉네임1", "111");

    // bjId | sendId | sendNick | month | chatNo
    static final String FOLLOW_ITEM_EFFECT =
            packet(ChatEvent.FOLLOW_ITEM_EFFECT, "bj1", "user1", "닉네임1", "27", "1000");

    // iceMode
    static final String ICE_MODE = packet(ChatEvent.ICE_MODE, "1");

    // iceMode | - | freezeType | balloonLimitCount | subscriptionLimitCount
    static final String ICE_MODE_EX = packet(ChatEvent.ICE_MODE_EX, "1", "0", "1008", "10", "3");

    // - | bjId | dropsName | dropsMsg | dropsImgUrl
    static final String ITEM_DROPS =
            packet(
                    ChatEvent.ITEM_DROPS,
                    "1000",
                    "bj1",
                    "상품1",
                    "hello",
                    "https://example.com/drops/item1.png");

    // chatNo | bjId | - | maxSubBjCount | familyNickname | - | userFlag
    static final String JOIN_CHANNEL =
            packet(ChatEvent.JOIN_CHANNEL, "1000", "bj1", "0", "10", "패밀리1", "0", "16|16384");

    // chatNo | isHideKickMessage("1"이면 true)
    static final String KICK_MSG_STATE = packet(ChatEvent.KICK_MSG_STATE, "1000", "1");

    // userId | userFlag
    static final String LOGIN = packet(ChatEvent.LOGIN, "viewer1", "16|0");

    // JSON 객체
    static final String MISSION =
            packet(
                    ChatEvent.MISSION,
                    "{\"type\":\"CHALLENGE_NOTICE\",\"mission_status\":\"FAIL\","
                            + "\"title\":\"미션1\",\"key\":1}");

    // JSON 객체
    static final String MISSION_SETTLE =
            packet(ChatEvent.MISSION_SETTLE, "{\"chno\":1000,\"list\":[[\"user1\",\"닉네임1\",100]]}");

    // chatNo | message | groupId | subId | version | userInfo(발신자 ID) | color(발신자 닉네임) |
    // chatLang | type
    static final String OGQ_EMOTICON =
            packet(
                    ChatEvent.OGQ_EMOTICON,
                    "1000",
                    "hello",
                    "ogqgroup1",
                    "3",
                    "1",
                    "user1",
                    "닉네임1",
                    "2",
                    "4");

    // - | senderId | senderNick | receivedId | receivedNick | ogqTitle | ogqImageUrl
    static final String OGQ_EMOTICON_GIFT =
            packet(
                    ChatEvent.OGQ_EMOTICON_GIFT,
                    "1000",
                    "user1",
                    "닉네임1",
                    "bj1",
                    "닉네임2",
                    "스티커1",
                    "https://example.com/ogq/sticker1.png");

    // bjId | senderId | senderNickname | count | fanOrder | - | - | fileName |
    // isDefault("1"이면 true) | isTopFan | ttsData
    static final String SEND_BALLOON =
            packet(
                    ChatEvent.SEND_BALLOON,
                    "bj1",
                    "user1",
                    "닉네임1",
                    "100",
                    "2",
                    "0",
                    "0",
                    "balloon1",
                    "1",
                    "3",
                    "tts1");

    // - | senderId | senderNickname | receiverId | receiverNickname | itemType
    static final String SEND_QUICK_VIEW =
            packet(ChatEvent.SEND_QUICK_VIEW, "1000", "user1", "닉네임1", "viewer1", "닉네임2", "101");

    // - | senderId | senderNickname | receiverId | receiverNickname | subscriptionId |
    // subscriptionNickname | itemType | itemCode | isSubscription | subscriptionType |
    // subscriptionPeriod | subscriptionRemain | subscriptionPaycount
    static final String SEND_SUBSCRIPTION =
            packet(
                    ChatEvent.SEND_SUBSCRIPTION,
                    "1000",
                    "user1",
                    "닉네임1",
                    "viewer1",
                    "닉네임2",
                    "bj1",
                    "닉네임3",
                    "11",
                    "code1",
                    "1",
                    "0",
                    "3",
                    "30",
                    "2");

    // 디코더가 필드를 읽지 않는다(공통 필드만)
    static final String SET_BJ_STAT = packet(ChatEvent.SET_BJ_STAT, "0");

    // userId | userInfo | dumbTime | dumbCount | adminId | adminType | extraInfo | userNickname
    static final String SET_DUMB =
            packet(ChatEvent.SET_DUMB, "user1", "info1", "30", "1", "bj1", "2", "extra1", "닉네임1");

    // userId | newNickname | changeType | flag | oldNickname
    static final String SET_NICKNAME =
            packet(ChatEvent.SET_NICKNAME, "user1", "닉네임2", "1", "65536|16384", "닉네임1");

    // userId | flag | hide | nickname
    static final String SET_SUB_BJ = packet(ChatEvent.SET_SUB_BJ, "user1", "4|16384", "1", "닉네임1");

    // oldFlag | userId | userNickname | - | - | newFlag
    static final String SET_USER_FLAG =
            packet(ChatEvent.SET_USER_FLAG, "16|16384", "user1", "닉네임1", "0", "0", "48|16384");

    // state
    static final String TRANSLATION_STATE = packet(ChatEvent.TRANSLATION_STATE, "1");

    // chatNo | bjId | userId | userNickname | balloonCount | fanOrder | - | isTopFan | relay | - |
    // - | - | fileName | isDefault("1"이면 true) | extraData
    static final String VIDEO_BALLOON =
            packet(
                    ChatEvent.VIDEO_BALLOON,
                    "1000",
                    "bj1",
                    "user1",
                    "닉네임1",
                    "50",
                    "3",
                    "0",
                    "4",
                    "2",
                    "0",
                    "0",
                    "0",
                    "video1",
                    "1",
                    "00112233445566778899aabbccddeeff");

    /** 위 패킷 전부. 이벤트 타입마다 하나씩 있습니다. */
    static final List<Sample> ALL =
            List.of(
                    new Sample(ChatEvent.ADCON_EFFECT, AdconEffectEvent.class, ADCON_EFFECT),
                    new Sample(ChatEvent.BAN_WORD, BanWordEvent.class, BAN_WORD),
                    new Sample(ChatEvent.BJ_NOTICE, BjNoticeEvent.class, BJ_NOTICE),
                    new Sample(
                            ChatEvent.BJ_STICKER_ITEM, BjStickerItemEvent.class, BJ_STICKER_ITEM),
                    new Sample(ChatEvent.CHAT_MESSAGE, ChatMessageEvent.class, CHAT_MESSAGE),
                    new Sample(ChatEvent.CHAT_USER, ChatUserEvent.class, CHAT_USER),
                    new Sample(ChatEvent.CHUSER_EXTEND, ChuserExtendEvent.class, CHUSER_EXTEND),
                    new Sample(
                            ChatEvent.EMOTICON_TICKET, EmoticonTicketEvent.class, EMOTICON_TICKET),
                    new Sample(ChatEvent.FOLLOW_ITEM, FollowItemEvent.class, FOLLOW_ITEM),
                    new Sample(
                            ChatEvent.FOLLOW_ITEM_EFFECT,
                            FollowItemEffectEvent.class,
                            FOLLOW_ITEM_EFFECT),
                    new Sample(ChatEvent.ICE_MODE, IceModeEvent.class, ICE_MODE),
                    new Sample(ChatEvent.ICE_MODE_EX, IceModeExEvent.class, ICE_MODE_EX),
                    new Sample(ChatEvent.ITEM_DROPS, ItemDropsEvent.class, ITEM_DROPS),
                    new Sample(ChatEvent.JOIN_CHANNEL, JoinChannelEvent.class, JOIN_CHANNEL),
                    new Sample(ChatEvent.KICK_MSG_STATE, KickMsgStateEvent.class, KICK_MSG_STATE),
                    new Sample(ChatEvent.LOGIN, LoginEvent.class, LOGIN),
                    new Sample(ChatEvent.MISSION, MissionEvent.class, MISSION),
                    new Sample(ChatEvent.MISSION_SETTLE, MissionSettleEvent.class, MISSION_SETTLE),
                    new Sample(ChatEvent.OGQ_EMOTICON, OGQEmoticonEvent.class, OGQ_EMOTICON),
                    new Sample(
                            ChatEvent.OGQ_EMOTICON_GIFT,
                            GiftOGQEmoticonEvent.class,
                            OGQ_EMOTICON_GIFT),
                    new Sample(ChatEvent.SEND_BALLOON, SendBalloonEvent.class, SEND_BALLOON),
                    new Sample(ChatEvent.SEND_QUICK_VIEW, QuickViewEvent.class, SEND_QUICK_VIEW),
                    new Sample(
                            ChatEvent.SEND_SUBSCRIPTION,
                            SendSubscriptionEvent.class,
                            SEND_SUBSCRIPTION),
                    new Sample(ChatEvent.SET_BJ_STAT, SetBjStatEvent.class, SET_BJ_STAT),
                    new Sample(ChatEvent.SET_DUMB, SetDumbEvent.class, SET_DUMB),
                    new Sample(ChatEvent.SET_NICKNAME, SetNicknameEvent.class, SET_NICKNAME),
                    new Sample(ChatEvent.SET_SUB_BJ, SetSubBjEvent.class, SET_SUB_BJ),
                    new Sample(ChatEvent.SET_USER_FLAG, SetUserFlagEvent.class, SET_USER_FLAG),
                    new Sample(
                            ChatEvent.TRANSLATION_STATE,
                            TranslationStateEvent.class,
                            TRANSLATION_STATE),
                    new Sample(ChatEvent.VIDEO_BALLOON, VideoBalloonEvent.class, VIDEO_BALLOON));

    private SyntheticPackets() {}

    /**
     * 서버 형식의 raw 패킷을 만듭니다. 길이 필드는 payload의 UTF-8 바이트 수입니다.
     *
     * @throws IllegalArgumentException 필드에 필드 구분자(F)나 ESC가 들어 있는 경우
     */
    static String packet(ChatEvent event, String... fields) {
        StringBuilder payload = new StringBuilder(SOOPConstants.F);
        for (String field : fields) {
            if (field.indexOf(SOOPConstants.F_CHAR) >= 0 || field.indexOf('\u001b') >= 0) {
                throw new IllegalArgumentException("Field breaks packet framing: " + field);
            }
            payload.append(field).append(SOOPConstants.F);
        }
        int byteLength = payload.toString().getBytes(StandardCharsets.UTF_8).length;
        return String.format(
                Locale.ROOT,
                "%s%04d%06d00%s",
                SOOPConstants.ESC,
                event.getCode(),
                byteLength,
                payload);
    }
}
