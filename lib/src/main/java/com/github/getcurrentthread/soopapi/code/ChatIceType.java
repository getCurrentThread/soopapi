package com.github.getcurrentthread.soopapi.code;

import java.util.EnumSet;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * 채팅 아이스(freeze)/제한 모드 코드표.
 *
 * <p>아이스 값은 구조가 다른 두 그룹으로 나뉩니다.
 *
 * <ul>
 *   <li><b>레거시 그룹</b> — 정수 전체가 하나의 코드(0~4)인 순차 값. 이 {@code enum}이 표현합니다.
 *   <li><b>v2 그룹</b> — OR 결합 가능한 비트 플래그(16/32/64/128/256). 중첩된 {@link Flag}가 표현합니다.
 * </ul>
 *
 * <p>두 그룹 모두 {@code 0}을 "해제(clear)"로 사용하므로(레거시 {@code CLEAR}, v2 {@code CLEAR2}) {@code 0}은 모호하지
 * 않습니다. {@link #fromCode(int)}는 {@code 0}을 레거시 {@link #CLEAR}로, {@link Flag#fromMask(int)}는 빈 집합으로
 * 결정적으로 처리합니다.
 *
 * @see com.github.getcurrentthread.soopapi.event.model.IceModeEvent
 */
public enum ChatIceType {

    /** 해제. */
    CLEAR(0, "Clear"),
    /** 전체 채팅 금지. */
    NORMAL(1, "Normal"),
    /** 팬클럽만 허용. */
    FAN(2, "Fan Only"),
    /** 구독자만 허용. */
    SUP(3, "Subscriber Only"),
    /** 팬클럽 + 구독자 허용. */
    FAN_SUP(4, "Fan + Subscriber"),

    /** 알 수 없는 코드를 위한 센티넬. */
    UNKNOWN(-1, "Unknown");

    private static final Map<Integer, ChatIceType> CODE_MAP;

    static {
        var map = new HashMap<Integer, ChatIceType>();
        for (ChatIceType t : values()) {
            map.put(t.code, t);
        }
        CODE_MAP = Map.copyOf(map);
    }

    private final int code;
    private final String description;

    ChatIceType(int code, String description) {
        this.code = code;
        this.description = description;
    }

    /**
     * 레거시 그룹(0~4) 코드를 {@link ChatIceType}로 매핑합니다. 알 수 없는 코드는 {@link #UNKNOWN}을 반환합니다.
     *
     * @param code 아이스 모드 코드
     * @return 매핑된 상수 또는 {@link #UNKNOWN}
     */
    public static ChatIceType fromCode(int code) {
        return CODE_MAP.getOrDefault(code, UNKNOWN);
    }

    /**
     * 해당 정수를 v2 비트 플래그 그룹으로 해석해야 하는지 여부.
     *
     * @param iceMode 아이스 모드 정수
     * @return {@code 16} 이상이면 {@code true}(v2 플래그), 0~4이면 {@code false}(레거시)
     */
    public static boolean isFlagMode(int iceMode) {
        return iceMode >= 16;
    }

    public int getCode() {
        return code;
    }

    public String getDescription() {
        return description;
    }

    /** 아이스 모드 v2 비트 플래그. */
    public enum Flag {
        /** 해제(비트 없음). 분해 시 빈 상태를 의미하므로 {@link #fromMask(int)}에서 제외됩니다. */
        CLEAR2(0),
        /** 전체 채팅 금지. */
        NORMAL2(16),
        /** 팬클럽. */
        FAN2(32),
        /** 구독자. */
        SUP2(64),
        /** 열혈팬. */
        TOP_FAN2(128),
        /** 구독(팔로워). */
        FOLLOWER2(256);

        private final int bit;

        Flag(int bit) {
            this.bit = bit;
        }

        public int getBit() {
            return bit;
        }

        /**
         * v2 마스크를 켜져 있는 플래그 집합으로 분해합니다. {@link #CLEAR2}(0)는 "해제" 상태를 뜻하므로 결과에서 제외됩니다.
         *
         * <p>상수에 매핑되지 않은 비트(예: {@code 512})는 조용히 무시됩니다. 예를 들어 {@code fromMask(1008)} ({@code
         * 16|32|64|128|256|512})은 {@code 512}를 뺀 다섯 플래그만 반환합니다.
         *
         * @param mask 아이스 모드 정수
         * @return 마스크에 포함된 플래그 집합(선언 순서)
         */
        public static Set<Flag> fromMask(int mask) {
            EnumSet<Flag> set = EnumSet.noneOf(Flag.class);
            for (Flag f : values()) {
                if (f.bit != 0 && (mask & f.bit) == f.bit) {
                    set.add(f);
                }
            }
            return set;
        }
    }
}
