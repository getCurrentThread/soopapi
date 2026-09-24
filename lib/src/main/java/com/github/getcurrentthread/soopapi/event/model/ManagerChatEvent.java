package com.github.getcurrentthread.soopapi.event.model;

import com.github.getcurrentthread.soopapi.code.UserLevel;
import com.github.getcurrentthread.soopapi.event.ChatEvent;

public record ManagerChatEvent(
        String message,
        String senderId,
        int isAdmin,
        int chatLang,
        String senderNickname,
        String senderFlag,
        String subscriptionMonth,
        ChatEvent eventType,
        String raw,
        long timestamp)
        implements ChatBaseEvent {

    /** {@code senderFlag}("primary|secondary")를 {@link UserLevel}로 지연 파싱합니다. */
    public UserLevel senderLevel() {
        return UserLevel.parse(senderFlag);
    }
}
