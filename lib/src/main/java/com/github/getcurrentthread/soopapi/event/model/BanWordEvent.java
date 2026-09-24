package com.github.getcurrentthread.soopapi.event.model;

import java.util.List;

import com.github.getcurrentthread.soopapi.event.ChatEvent;

/**
 * 금지어 설정 이벤트.
 *
 * @param replaceWord 금지어 대신 표시할 대체어
 * @param banWordList 금지어 목록. 수정할 수 없는 리스트이며 {@code null}을 넘기면 빈 리스트가 되고, {@code null} 요소는 허용하지
 *     않습니다.
 */
public record BanWordEvent(
        String replaceWord,
        List<String> banWordList,
        ChatEvent eventType,
        String raw,
        long timestamp)
        implements ModerationBaseEvent {

    public BanWordEvent {
        banWordList = banWordList == null ? List.of() : List.copyOf(banWordList);
    }
}
