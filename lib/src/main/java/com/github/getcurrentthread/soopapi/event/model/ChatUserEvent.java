package com.github.getcurrentthread.soopapi.event.model;

import java.util.List;

import com.github.getcurrentthread.soopapi.code.UserLevel;
import com.github.getcurrentthread.soopapi.event.ChatEvent;

/**
 * 채팅 사용자 입장/퇴장 이벤트.
 *
 * @param userList 사용자 목록. 수정할 수 없는 리스트이며 {@code null}을 넘기면 빈 리스트가 되고, {@code null} 요소는 허용하지 않습니다.
 */
public record ChatUserEvent(
        int type, List<ChatUserEntry> userList, ChatEvent eventType, String raw, long timestamp)
        implements SystemBaseEvent {

    public ChatUserEvent {
        userList = userList == null ? List.of() : List.copyOf(userList);
    }

    public record ChatUserEntry(String id, String nickname, String flag) {

        /** {@code flag}("primary|secondary")를 {@link UserLevel}로 지연 파싱합니다. */
        public UserLevel level() {
            return UserLevel.parse(flag);
        }
    }
}
