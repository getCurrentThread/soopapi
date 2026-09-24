package com.github.getcurrentthread.soopapi.event.model;

import com.github.getcurrentthread.soopapi.event.ChatEvent;

/**
 * 등록된 디코더가 없는 서비스 코드의 패킷입니다.
 *
 * <p>기본 팩토리는 알려진 서버 이벤트에만 디코더를 등록하므로, {@link ChatEvent#fromCode(int)}가 모르는 코드의 패킷은 이 이벤트로 {@link
 * ChatEvent#NONE_TYPE} 리스너에 전달됩니다. 원래 코드는 {@link #code()}로 확인합니다.
 *
 * <p>{@link SystemBaseEvent}가 아니라 {@link BaseEvent}를 직접 구현합니다. {@link ChatEvent#NONE_TYPE} 리스너는 이
 * 타입이나 {@link BaseEvent}로 받아야 하며, {@link SystemBaseEvent}로 받으면 캐스팅에 실패합니다.
 *
 * @param code 패킷 헤더에서 읽은 원래 서비스 코드
 * @param originalMessage 수신한 패킷 전체. {@link #raw()}와 같은 값입니다.
 * @param eventType 항상 {@link ChatEvent#NONE_TYPE}
 */
public record UnknownEvent(
        int code, String originalMessage, ChatEvent eventType, String raw, long timestamp)
        implements BaseEvent {}
