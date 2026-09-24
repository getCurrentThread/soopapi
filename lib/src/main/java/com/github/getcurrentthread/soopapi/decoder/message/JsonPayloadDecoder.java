package com.github.getcurrentthread.soopapi.decoder.message;

import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

import com.github.getcurrentthread.soopapi.event.model.BaseEvent;
import com.github.getcurrentthread.soopapi.util.GsonUtil;

/**
 * 첫 번째 필드에 JSON 객체를 싣는 메시지의 공통 디코더입니다.
 *
 * <p>{@code parts[0]}을 {@link GsonUtil#fromJson(String)}으로 파싱해 {@link #create(Map, String)}에 넘깁니다.
 * 필드가 없거나, JSON이 {@code null}이거나, 파싱에 실패하면 {@code null}을 반환합니다. 파싱 실패는 payload 앞부분과 함께 {@link
 * Level#FINE}으로 기록합니다.
 */
abstract class JsonPayloadDecoder implements IMessageDecoder {
    private static final Logger LOGGER = Logger.getLogger(JsonPayloadDecoder.class.getName());
    private static final int MAX_LOGGED_PAYLOAD = 200;

    @Override
    public BaseEvent decode(String[] parts, String raw) {
        if (parts.length == 0) {
            return null;
        }
        String payload = parts[0];
        Map<String, Object> data;
        try {
            data = GsonUtil.fromJson(payload);
        } catch (RuntimeException e) {
            if (LOGGER.isLoggable(Level.FINE)) {
                String truncated =
                        payload.length() > MAX_LOGGED_PAYLOAD
                                ? payload.substring(0, MAX_LOGGED_PAYLOAD) + "..."
                                : payload;
                LOGGER.log(
                        Level.FINE,
                        getClass().getSimpleName() + " failed to parse JSON payload: " + truncated,
                        e);
            }
            return null;
        }
        if (data == null) {
            return null;
        }
        return create(data, raw);
    }

    /** 파싱한 JSON 객체로 이벤트를 만듭니다. {@code data}는 {@code null}이 아닙니다. */
    abstract BaseEvent create(Map<String, Object> data, String raw);
}
