package com.github.getcurrentthread.soopapi.event.model;

import com.github.getcurrentthread.soopapi.code.UserLevel;
import com.github.getcurrentthread.soopapi.event.ChatEvent;

public record SetSubBjEvent(
        String userId,
        String flag,
        int hide,
        String nickname,
        ChatEvent eventType,
        String raw,
        long timestamp)
        implements SystemBaseEvent {

    /** {@code flag}("primary|secondary")를 {@link UserLevel}로 지연 파싱합니다. */
    public UserLevel level() {
        return UserLevel.parse(flag);
    }
}
