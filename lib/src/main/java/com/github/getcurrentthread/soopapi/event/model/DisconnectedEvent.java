package com.github.getcurrentthread.soopapi.event.model;

import com.github.getcurrentthread.soopapi.event.ChatEvent;

/**
 * 채팅 세션이 끝났음을 알립니다. 세션마다 정확히 한 번 발생합니다.
 *
 * @param statusCode WebSocket 종료 코드. 오류로 끝났으면 -1
 * @param reason 종료 사유
 * @param causedByError 연결 실패나 재시도 소진으로 끝났으면 true
 */
public record DisconnectedEvent(
        int statusCode,
        String reason,
        boolean causedByError,
        ChatEvent eventType,
        String raw,
        long timestamp)
        implements SystemBaseEvent {

    /** {@code disconnect()}/{@code close()}로 클라이언트가 직접 끊었을 때의 {@link #reason()}. */
    public static final String CLIENT_DISCONNECT_REASON = "Client disconnect";

    /** 클라이언트가 {@code disconnect()}/{@code close()}로 직접 끊었으면 true. 재연결 로직에서 이 경우를 건너뛸 때 씁니다. */
    public boolean isClientInitiated() {
        return !causedByError && CLIENT_DISCONNECT_REASON.equals(reason);
    }
}
