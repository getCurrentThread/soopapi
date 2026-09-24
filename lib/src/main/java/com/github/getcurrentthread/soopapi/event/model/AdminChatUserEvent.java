package com.github.getcurrentthread.soopapi.event.model;

import java.util.List;

import com.github.getcurrentthread.soopapi.code.UserLevel;
import com.github.getcurrentthread.soopapi.event.ChatEvent;

/**
 * 관리자용 채팅 사용자 목록 이벤트.
 *
 * @param users 사용자 목록. 수정할 수 없는 리스트이며 {@code null}을 넘기면 빈 리스트가 되고, {@code null} 요소는 허용하지 않습니다.
 */
public record AdminChatUserEvent(
        String type,
        List<AdminChatUserEntry> users,
        ChatEvent eventType,
        String raw,
        long timestamp)
        implements ModerationBaseEvent {

    public AdminChatUserEvent {
        users = users == null ? List.of() : List.copyOf(users);
    }

    public record AdminChatUserEntry(String id, String nickname, String flag) {

        /** {@code flag}("primary|secondary")를 {@link UserLevel}로 지연 파싱합니다. */
        public UserLevel level() {
            return UserLevel.parse(flag);
        }
    }
}
