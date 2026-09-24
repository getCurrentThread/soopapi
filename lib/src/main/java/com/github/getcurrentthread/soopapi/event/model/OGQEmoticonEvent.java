package com.github.getcurrentthread.soopapi.event.model;

import com.github.getcurrentthread.soopapi.event.ChatEvent;

/**
 * OGQ 이모티콘 전송 이벤트.
 *
 * <p>{@code userInfo}와 {@code color}는 초기 구현에서 붙인 이름이 그대로 남은 컴포넌트입니다. 실제로는 {@code userInfo}에 발신자
 * ID가, {@code color}에 발신자 닉네임이 들어옵니다. 새 코드에서는 의미가 드러나는 {@link #senderId()}와 {@link
 * #senderNickname()}을 쓰세요.
 */
public record OGQEmoticonEvent(
        String chatNo,
        String message,
        String groupId,
        String subId,
        String version,
        String userInfo,
        String color,
        String chatLang,
        String type,
        ChatEvent eventType,
        String raw,
        long timestamp)
        implements ItemBaseEvent {

    /** 발신자 ID를 반환합니다. 이전 이름인 {@link #userInfo()}와 같은 값입니다. */
    public String senderId() {
        return userInfo;
    }

    /** 발신자 닉네임을 반환합니다. 이전 이름인 {@link #color()}와 같은 값입니다. */
    public String senderNickname() {
        return color;
    }
}
