package com.github.getcurrentthread.soopapi.event.model;

import java.util.List;

import com.github.getcurrentthread.soopapi.event.ChatEvent;

/**
 * 강퇴 사용자 목록 이벤트.
 *
 * @param kickedUsers 강퇴된 사용자 목록. 수정할 수 없는 리스트이며 {@code null}을 넘기면 빈 리스트가 되고, {@code null} 요소는 허용하지
 *     않습니다.
 */
public record KickUserListEvent(
        List<KickedUser> kickedUsers, ChatEvent eventType, String raw, long timestamp)
        implements ModerationBaseEvent {

    public KickUserListEvent {
        kickedUsers = kickedUsers == null ? List.of() : List.copyOf(kickedUsers);
    }

    public record KickedUser(
            String userId,
            String userNickname,
            String time,
            String orderUserId,
            String orderUserNickname,
            String orderUserFlag) {}
}
