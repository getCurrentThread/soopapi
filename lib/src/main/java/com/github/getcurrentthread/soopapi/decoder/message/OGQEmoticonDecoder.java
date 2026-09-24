package com.github.getcurrentthread.soopapi.decoder.message;

import com.github.getcurrentthread.soopapi.event.ChatEvent;
import com.github.getcurrentthread.soopapi.event.model.BaseEvent;
import com.github.getcurrentthread.soopapi.event.model.OGQEmoticonEvent;

public class OGQEmoticonDecoder implements IMessageDecoder {
    private static final int MIN_PARTS = 6;

    @Override
    public BaseEvent decode(String[] parts, String raw) {
        if (parts.length < MIN_PARTS) {
            return null;
        }
        String color = parts.length > 6 ? parts[6] : "";
        String chatLang = parts.length > 7 ? parts[7] : "";
        String type = parts.length > 8 ? parts[8] : "";

        return new OGQEmoticonEvent(
                parts[0],
                parts[1],
                parts[2],
                parts[3],
                parts[4],
                parts[5],
                color,
                chatLang,
                type,
                ChatEvent.OGQ_EMOTICON,
                raw,
                System.currentTimeMillis());
    }
}
