package com.github.getcurrentthread.soopapi.event.model;

import java.util.Set;

import com.github.getcurrentthread.soopapi.code.ChatIceType;
import com.github.getcurrentthread.soopapi.event.ChatEvent;

public record GetIceModeRelayEvent(
        int iceMode, int freezeType, ChatEvent eventType, String raw, long timestamp)
        implements SystemBaseEvent {

    /** {@code iceMode}를 레거시 {@link ChatIceType}로 지연 변환합니다. */
    public ChatIceType iceType() {
        return ChatIceType.fromCode(iceMode);
    }

    /** {@code iceMode}를 v2 비트 플래그 집합으로 지연 분해합니다. */
    public Set<ChatIceType.Flag> iceFlags() {
        return ChatIceType.Flag.fromMask(iceMode);
    }

    /** {@code iceMode}를 v2 플래그로 해석해야 하는지 여부. */
    public boolean isIceFlagMode() {
        return ChatIceType.isFlagMode(iceMode);
    }

    /**
     * {@code freezeType}을 v2 비트 플래그 집합으로 지연 분해합니다.
     *
     * <p>관측된 패킷에서 {@code iceMode}는 {@code 0}/{@code 1}의 켜짐/꺼짐 값으로 오고, 그룹 비트마스크는 {@code freezeType}에
     * 실려 왔습니다. {@link #iceFlags()}는 {@code iceMode}만 분해하므로 그룹 정보가 필요하면 이 메서드를 사용하세요. 매핑되지 않은 비트는
     * 무시됩니다({@link ChatIceType.Flag#fromMask(int)} 참고).
     */
    public Set<ChatIceType.Flag> freezeFlags() {
        return ChatIceType.Flag.fromMask(freezeType);
    }
}
