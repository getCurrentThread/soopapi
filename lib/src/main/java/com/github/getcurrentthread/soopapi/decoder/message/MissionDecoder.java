package com.github.getcurrentthread.soopapi.decoder.message;

import java.util.Map;

import com.github.getcurrentthread.soopapi.event.ChatEvent;
import com.github.getcurrentthread.soopapi.event.model.BaseEvent;
import com.github.getcurrentthread.soopapi.event.model.MissionEvent;

public class MissionDecoder extends JsonPayloadDecoder {
    @Override
    BaseEvent create(Map<String, Object> data, String raw) {
        return new MissionEvent(data, ChatEvent.MISSION, raw, System.currentTimeMillis());
    }
}
