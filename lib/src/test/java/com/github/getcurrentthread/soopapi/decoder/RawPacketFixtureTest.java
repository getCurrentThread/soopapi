package com.github.getcurrentthread.soopapi.decoder;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import com.github.getcurrentthread.soopapi.constant.SOOPConstants;
import com.github.getcurrentthread.soopapi.decoder.factory.DefaultMessageDecoderFactory;
import com.github.getcurrentthread.soopapi.event.ChatEvent;
import com.github.getcurrentthread.soopapi.event.EventEmitter;
import com.github.getcurrentthread.soopapi.event.EventListener;
import com.github.getcurrentthread.soopapi.event.model.BaseEvent;
import com.github.getcurrentthread.soopapi.event.model.RawEvent;
import com.github.getcurrentthread.soopapi.util.SOOPChatUtils;

/**
 * {@link SyntheticPackets}의 모든 합성 raw 패킷을 실제 디코더 맵과 {@link MessageDispatcher}로 처리해, RAW 이벤트와 기대한
 * 타입의 이벤트가 각각 정확히 하나 발행되는지 검증합니다.
 *
 * <p>패킷은 테스트 코드에서 만들므로 항상 실행됩니다. DataCollector({@code collectFixtures} 태스크)가 로컬에 수집하는 {@code
 * fixtures/*.bin}은 읽지 않습니다.
 */
class RawPacketFixtureTest {

    static Stream<Arguments> syntheticPackets() {
        return SyntheticPackets.ALL.stream().map(s -> Arguments.of(s.event(), s.type(), s.raw()));
    }

    @ParameterizedTest(name = "[{0}] raw 패킷이 올바르게 처리됨")
    @MethodSource("syntheticPackets")
    void rawPacket_dispatchedCorrectly(
            ChatEvent expectedEvent, Class<? extends BaseEvent> expectedType, String rawPacket) {
        // 헤더: ESC + 서비스 코드(4) + payload 바이트 길이(6) + "00", payload는 F로 시작
        int firstSep = rawPacket.indexOf(SOOPConstants.F_CHAR);
        String header = rawPacket.substring(0, firstSep);
        assertTrue(header.startsWith(SOOPConstants.ESC), "패킷이 ESC로 시작하지 않음");
        assertEquals(SOOPConstants.ESC.length() + 12, firstSep, "헤더 길이가 서버 형식과 다름");
        assertEquals(expectedEvent.getCode(), SOOPChatUtils.parseServiceCode(header));
        String payload = rawPacket.substring(firstSep);
        assertEquals(
                payload.getBytes(StandardCharsets.UTF_8).length,
                Integer.parseInt(header.substring(SOOPConstants.ESC.length() + 4, firstSep - 2)),
                "길이 필드가 payload의 UTF-8 바이트 수와 다름");
        assertTrue(header.endsWith("00"));

        EventEmitter emitter = new EventEmitter();
        MessageDispatcher dispatcher =
                new MessageDispatcher(
                        new DefaultMessageDecoderFactory().createDecoders(),
                        Runnable::run,
                        emitter);

        // RAW와 나머지 모든 이벤트를 따로 모아 기대한 이벤트 외에는 아무것도 나오지 않는지 확인한다.
        List<BaseEvent> raws = new ArrayList<>();
        List<BaseEvent> decoded = new ArrayList<>();
        for (ChatEvent event : ChatEvent.values()) {
            List<BaseEvent> sink = event == ChatEvent.RAW ? raws : decoded;
            emitter.on(event, (EventListener<BaseEvent>) sink::add);
        }

        dispatcher.dispatchMessage(rawPacket);

        assertEquals(1, raws.size(), "RAW 이벤트가 정확히 하나 발행되어야 함");
        RawEvent raw = assertInstanceOf(RawEvent.class, raws.get(0));
        assertEquals(ChatEvent.RAW, raw.eventType());
        assertEquals(rawPacket, raw.raw());

        assertEquals(1, decoded.size(), "디코딩된 이벤트가 정확히 하나 발행되어야 함: " + decoded);
        BaseEvent event = decoded.get(0);
        assertInstanceOf(expectedType, event);
        assertEquals(expectedEvent, event.eventType());
        assertEquals(rawPacket, event.raw());
        assertTrue(event.timestamp() > 0, "timestamp가 0 이하");
    }

    @Test
    void syntheticPackets_haveOnePacketPerEventType() {
        List<ChatEvent> events =
                SyntheticPackets.ALL.stream().map(SyntheticPackets.Sample::event).toList();

        assertEquals(30, events.size());
        assertEquals(events.size(), Set.copyOf(events).size(), "같은 이벤트 타입이 두 번 들어 있음");
    }
}
