package com.github.getcurrentthread.soopapi.event.model;

import com.github.getcurrentthread.soopapi.code.UserLevel;
import com.github.getcurrentthread.soopapi.event.ChatEvent;

public record SetNicknameEvent(
        String userId,
        String newNickname,
        int changeType,
        String flag,
        String oldNickname,
        ChatEvent eventType,
        String raw,
        long timestamp)
        implements SystemBaseEvent {

    /** {@code flag}("primary|secondary")를 {@link UserLevel}로 지연 파싱합니다. */
    public UserLevel level() {
        return UserLevel.parse(flag);
    }
}
