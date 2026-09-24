package com.github.getcurrentthread.soopapi.event.model;

import java.util.Map;
import java.util.stream.Collectors;

import com.github.getcurrentthread.soopapi.event.ChatEvent;

/**
 * 채팅 사용자 확장 정보 이벤트.
 *
 * @param userStatus 사용자 ID별 상태 값({@code key=value}). 바깥 맵과 안쪽 맵 모두 수정할 수 없으며 {@code null}을 넘기면 빈 맵이
 *     되고, {@code null} 키·값은 허용하지 않습니다.
 */
public record ChuserExtendEvent(
        Map<String, Map<String, Integer>> userStatus,
        ChatEvent eventType,
        String raw,
        long timestamp)
        implements SystemBaseEvent {

    public ChuserExtendEvent {
        userStatus =
                userStatus == null
                        ? Map.of()
                        : userStatus.entrySet().stream()
                                .collect(
                                        Collectors.toUnmodifiableMap(
                                                Map.Entry::getKey, e -> Map.copyOf(e.getValue())));
    }
}
