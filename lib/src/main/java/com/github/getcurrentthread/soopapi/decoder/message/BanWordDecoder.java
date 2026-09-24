package com.github.getcurrentthread.soopapi.decoder.message;

import java.util.Arrays;

import com.github.getcurrentthread.soopapi.event.ChatEvent;
import com.github.getcurrentthread.soopapi.event.model.BanWordEvent;
import com.github.getcurrentthread.soopapi.event.model.BaseEvent;

public class BanWordDecoder implements IMessageDecoder {
    @Override
    public BaseEvent decode(String[] parts, String raw) {
        String replaceWord = parts.length > 0 ? parts[0] : "";
        String field = parts.length > 1 ? parts[1] : "";
        // 빈 필드와 ",,"가 만드는 빈 토큰은 버린다. 공백의 의미는 알 수 없으므로 trim하지 않는다.
        String[] banWordList =
                field.isEmpty()
                        ? new String[0]
                        : Arrays.stream(field.split(","))
                                .filter(s -> !s.isEmpty())
                                .toArray(String[]::new);

        return new BanWordEvent(
                replaceWord, banWordList, ChatEvent.BAN_WORD, raw, System.currentTimeMillis());
    }
}
