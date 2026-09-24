package com.github.getcurrentthread.soopapi.event;

import java.util.HashMap;
import java.util.Map;

public enum ChatEvent {

    // ── 기본 연결 (0~2) ──
    KEEP_ALIVE(0, "Keep Alive"), // 핑퐁
    LOGIN(1, "Login Handshake"),
    JOIN_CHANNEL(2, "Channel Join Handshake"),

    // ── 채팅 / 사용자 (3~9) ──
    QUIT_CHANNEL(3, "Channel Leave"), // 강제 퇴장
    CHAT_USER(4, "Chat User Join/Leave"),
    CHAT_MESSAGE(5, "Chat Message"),
    SET_CHANNEL_NAME(6, "Set Channel Name"),
    SET_BJ_STAT(7, "Set BJ Status"), // 방송 연결 해제 시 수신
    SET_DUMB(8, "Chat Mute"), // 채금
    DIRECT_CHAT(9, "Direct Chat"),

    // ── 공지 / 관리 (10~17) ──
    NOTICE(10, "Notice"), // 미사용
    KICK(11, "Kick"), // 미사용
    SET_USER_FLAG(12, "Set User Flag"), // 입장 정보 변경
    SET_SUB_BJ(13, "Set Sub BJ"),
    SET_NICKNAME(14, "Set Nickname"),
    SERVER_STAT(15, "Server Status"), // 미사용
    NULL_16(16, "Unused"),
    CLUB_COLOR(17, "Club Color"),

    // ── 후원 / 별풍선 (18~22) ──
    SEND_BALLOON(18, "Send Star Balloon"),
    ICE_MODE(19, "Ice Mode"),
    SEND_FAN_LETTER(20, "Send Fan Letter"),
    ICE_MODE_EX(21, "Extended Ice Mode"), // 로그상 21→19 순서
    GET_ICE_MODE_RELAY(22, "Get Ice Mode Relay"), // 미사용

    // ── 채팅 제어 (23~27) ──
    SLOW_MODE(23, "Slow Mode"),
    RELOAD_BURN_LEVEL(24, "Reload Burn Level"), // 미사용
    BLIND_KICK(25, "Blind Kick"), // 미사용
    MANAGER_CHAT(26, "Manager Chat"), // 매니저 flag 이상만 수신 가능
    APPEND_DATA(27, "Append Data"), // 미사용

    // ── 이벤트 / 아이템 (28~48) ──
    BASEBALL_EVENT(28, "Baseball Event"), // 미사용
    PAID_ITEM(29, "Paid Item"), // 미사용
    TOP_FAN(30, "Top Fan"), // 미사용
    SNS_MESSAGE(31, "SNS Message"), // 미사용
    SNS_MODE(32, "SNS Mode"), // 미사용
    SEND_BALLOON_SUB(33, "Send Star Balloon (Sub)"),
    SEND_FAN_LETTER_SUB(34, "Send Fan Letter (Sub)"),
    TOP_FAN_SUB(35, "Top Fan (Sub)"), // 미사용
    BJ_STICKER_ITEM(36, "BJ Sticker Item"), // 미사용
    CHOCOLATE(37, "Chocolate"),
    CHOCOLATE_SUB(38, "Chocolate (Sub)"),
    TOP_CLAN(39, "Top Clan"), // 미사용
    TOP_CLAN_SUB(40, "Top Clan (Sub)"), // 미사용
    SUPER_CHAT(41, "Super Chat"), // 미사용
    UPDATE_TICKET(42, "Update Ticket"), // 미사용
    NOTI_GAME_RANKER(43, "Game Ranker Notification"), // 미사용
    STAR_COIN(44, "Star Coin"),
    SEND_QUICK_VIEW(45, "Send Quick View Gift"),
    ITEM_STATUS(46, "Item Status"), // 미사용
    ITEM_USING(47, "Item In Use"),
    USE_QUICK_VIEW(48, "Use Quick View"),

    // ── 투표 / 차단 / 방송 정보 (50~58) ──
    NOTIFY_POLL(50, "Poll Notification"),
    CHAT_BLOCK_MODE(51, "Chat Block Mode"), // 미사용
    BDM_ADD_BLACK_INFO(52, "Add Blacklist Info"),
    SET_BROAD_INFO(53, "Set Broadcast Info"), // 미사용
    BAN_WORD(54, "Ban Word Setting"),
    SEND_ADMIN_NOTICE(58, "Send Admin Notice"),

    // ── 프리캣 / 상품 / 프로모션 (65~75) ──
    FREECAT_OWNER_JOIN(65, "Freecat Owner Join"),
    BUY_GOODS(70, "Buy Goods"),
    BUY_GOODS_SUB(71, "Buy Goods (Sub)"),
    SEND_PROMOTION(72, "Send Promotion"), // 미사용
    NOTIFY_VR(74, "VR Notification"),
    NOTIFY_MOBBROAD_PAUSE(75, "Mobile Broadcast Pause Notification"),

    // ── 강퇴 / 관리자 (76~79) ──
    KICK_AND_CANCEL(76, "Kick and Cancel"),
    KICK_USERLIST(77, "Kick User List"),
    ADMIN_CHAT_USER(78, "Admin Chat User"),
    CLI_DOBAE_INFO(79, "Spam Info"),

    // ── 후원 / 애드콘 (86~87) ──
    VOD_BALLOON(86, "VOD Balloon"),
    ADCON_EFFECT(87, "Adcon Effect"), // 애드벌룬 후원

    // ── 구독 / 번역 (90~95) ──
    KICK_MSG_STATE(90, "Kick Message State"),
    FOLLOW_ITEM(91, "New Subscription"),
    ITEM_SELL_EFFECT(92, "Item Sell Effect"),
    FOLLOW_ITEM_EFFECT(93, "Continuous Subscription"),
    TRANSLATION_STATE(94, "Translation State"),
    TRANSLATION(95, "Translation"),

    // ── 티켓 / 공지 / 영상 후원 (102~109) ──
    GIFT_TICKET(102, "Gift Ticket"),
    VOD_ADCON(103, "VOD Adcon"),
    BJ_NOTICE(104, "BJ Notice"),
    VIDEO_BALLOON(105, "Video Donation"),
    STATION_ADCON(107, "Station Adcon"),
    SEND_SUBSCRIPTION(108, "Gift Subscription"),
    OGQ_EMOTICON(109, "OGQ Emoticon"),

    // ── 아이템 / 이모티콘 / 광고 (110~122) ──
    EMOTICON_TICKET(110, "Emoticon Ticket"), // 채널 입장 직후 수신, value=1
    ITEM_DROPS(111, "Item Drops"),
    VIDEO_BALLOON_LINK(117, "Video Balloon Link"), // 미사용
    OGQ_EMOTICON_GIFT(118, "OGQ Emoticon Gift"),
    AD_IN_BROAD_JSON(119, "In-Broadcast Ad JSON"),
    GEM_ITEM_SEND(120, "Gem Item Send"),
    MISSION(121, "Challenge Mission"),
    LIVE_CAPTION(122, "Live Caption"),

    // ── 미션 / 관리자 / 사용자 확장 (125~128) ──
    MISSION_SETTLE(125, "Mission Settlement"),
    SET_ADMIN_FLAG(126, "Set Admin Flag"),
    CHUSER_EXTEND(127, "Subscriber List"),
    ADMIN_CHUSER_EXTEND(128, "Admin Chat User Extended"),

    // ── 특수 이벤트 ──
    RAW(-2, "Raw Packet"),

    // ── 연결 상태 이벤트 ──
    DISCONNECTED(-3, "Disconnected"),
    RECONNECTING(-4, "Reconnecting"),
    RECONNECTED(-5, "Reconnected"),

    // ── 알 수 없는 타입 ──
    /**
     * 모르는 서비스 코드의 센티넬. 이런 패킷은 원래 코드를 담은 {@link
     * com.github.getcurrentthread.soopapi.event.model.UnknownEvent}로 전달됩니다.
     */
    NONE_TYPE(-1, "Unknown Type");

    private static final Map<Integer, ChatEvent> CODE_MAP;

    static {
        var map = new HashMap<Integer, ChatEvent>();
        for (ChatEvent e : values()) {
            map.put(e.code, e);
        }
        CODE_MAP = Map.copyOf(map);
    }

    private final int code;
    private final String description;

    ChatEvent(int code, String description) {
        this.code = code;
        this.description = description;
    }

    /** 서비스 코드에 해당하는 이벤트를 반환합니다. 모르는 코드면 {@link #NONE_TYPE}을 반환합니다. */
    public static ChatEvent fromCode(int code) {
        return CODE_MAP.getOrDefault(code, NONE_TYPE);
    }

    public int getCode() {
        return code;
    }

    public String getDescription() {
        return description;
    }
}
