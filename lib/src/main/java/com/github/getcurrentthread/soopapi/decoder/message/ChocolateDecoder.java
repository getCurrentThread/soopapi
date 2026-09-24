package com.github.getcurrentthread.soopapi.decoder.message;

import java.util.Objects;

import com.github.getcurrentthread.soopapi.event.ChatEvent;
import com.github.getcurrentthread.soopapi.event.model.BaseEvent;
import com.github.getcurrentthread.soopapi.event.model.ChocolateEvent;
import com.github.getcurrentthread.soopapi.util.SOOPChatUtils;

/**
 * 초콜릿 후원 메시지를 디코딩합니다.
 *
 * <p>{@link ChatEvent#CHOCOLATE}와 {@link ChatEvent#CHOCOLATE_SUB}를 한 클래스가 같은 필드 순서로 읽습니다. 생성자로 받은
 * 태그가 이벤트의 {@code eventType()}이 되므로, 팩토리에 등록하는 키와 같은 값을 넘겨야 합니다.
 */
public class ChocolateDecoder implements IMessageDecoder {
    private static final int MIN_PARTS = 5;

    private final ChatEvent tag;

    /** {@link ChatEvent#CHOCOLATE} 태그로 디코더를 만듭니다. */
    public ChocolateDecoder() {
        this(ChatEvent.CHOCOLATE);
    }

    /**
     * 지정한 태그로 디코더를 만듭니다.
     *
     * @param tag 디코딩한 이벤트의 {@code eventType()} (예: {@link ChatEvent#CHOCOLATE_SUB})
     */
    public ChocolateDecoder(ChatEvent tag) {
        this.tag = Objects.requireNonNull(tag, "tag");
    }

    @Override
    public BaseEvent decode(String[] parts, String raw) {
        if (parts.length < MIN_PARTS) {
            return null;
        }
        return new ChocolateEvent(
                parts[1],
                parts[2],
                parts[3],
                SOOPChatUtils.safeParseInt(parts[4], 0),
                tag,
                raw,
                System.currentTimeMillis());
    }
}
