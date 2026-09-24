package com.github.getcurrentthread.soopapi.decoder.message;

import java.util.Map;

import com.github.getcurrentthread.soopapi.event.ChatEvent;
import com.github.getcurrentthread.soopapi.event.model.AdInBroadJsonEvent;
import com.github.getcurrentthread.soopapi.event.model.BaseEvent;

public class AdInBroadJsonDecoder extends JsonPayloadDecoder {
    @Override
    BaseEvent create(Map<String, Object> data, String raw) {
        return new AdInBroadJsonEvent(
                data, ChatEvent.AD_IN_BROAD_JSON, raw, System.currentTimeMillis());
    }
}
