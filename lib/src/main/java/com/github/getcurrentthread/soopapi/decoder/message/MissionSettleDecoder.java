package com.github.getcurrentthread.soopapi.decoder.message;

import java.util.Map;

import com.github.getcurrentthread.soopapi.event.ChatEvent;
import com.github.getcurrentthread.soopapi.event.model.BaseEvent;
import com.github.getcurrentthread.soopapi.event.model.MissionSettleEvent;

public class MissionSettleDecoder extends JsonPayloadDecoder {
    @Override
    BaseEvent create(Map<String, Object> data, String raw) {
        return new MissionSettleEvent(
                data, ChatEvent.MISSION_SETTLE, raw, System.currentTimeMillis());
    }
}
