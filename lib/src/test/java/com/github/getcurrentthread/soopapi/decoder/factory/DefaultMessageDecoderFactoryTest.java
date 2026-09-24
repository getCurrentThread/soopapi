package com.github.getcurrentthread.soopapi.decoder.factory;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import com.github.getcurrentthread.soopapi.constant.SOOPConstants;
import com.github.getcurrentthread.soopapi.decoder.MessageDispatcher;
import com.github.getcurrentthread.soopapi.decoder.message.IMessageDecoder;
import com.github.getcurrentthread.soopapi.event.ChatEvent;
import com.github.getcurrentthread.soopapi.event.EventEmitter;
import com.github.getcurrentthread.soopapi.event.model.BaseEvent;
import com.github.getcurrentthread.soopapi.event.model.ChocolateEvent;
import com.github.getcurrentthread.soopapi.event.model.NoneTypeEvent;

class DefaultMessageDecoderFactoryTest {

    private static final int MAX_PARTS = 20;

    private static final Map<ChatEvent, IMessageDecoder> DECODERS =
            new DefaultMessageDecoderFactory().createDecoders();

    static Stream<Arguments> registrations() {
        return DECODERS.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(e -> Arguments.of(e.getKey(), e.getValue()));
    }

    // 등록 키와 디코더가 내보내는 eventType()이 다르면 *_SUB 같은 공유 디코더가 이벤트를 잘못 태깅한다.
    @ParameterizedTest(name = "{0}")
    @MethodSource("registrations")
    void decoder_neverThrowsAndTagsEventWithRegistrationKey(
            ChatEvent key, IMessageDecoder decoder) {
        for (int length = 0; length <= MAX_PARTS; length++) {
            String[] parts = new String[length];
            Arrays.fill(parts, "0");
            int n = length;

            BaseEvent event =
                    assertDoesNotThrow(
                            () -> decoder.decode(parts, "raw"),
                            () -> key + " threw for " + n + " parts");

            if (event != null) {
                assertEquals(key, event.eventType(), () -> key + " with " + n + " parts");
            }
        }
    }

    @Test
    void everyProtocolEvent_hasRegisteredDecoder() {
        List<ChatEvent> missing =
                Arrays.stream(ChatEvent.values())
                        .filter(e -> e.getCode() >= 0)
                        .filter(e -> !DECODERS.containsKey(e))
                        .toList();

        assertEquals(List.of(), missing, "ChatEvent codes without a decoder");
    }

    @Test
    void chocolateSub_isTaggedAsChocolateSub() {
        String[] parts = {"0", "bj1", "user1", "nick1", "3"};

        BaseEvent main = DECODERS.get(ChatEvent.CHOCOLATE).decode(parts, "raw");
        BaseEvent sub = DECODERS.get(ChatEvent.CHOCOLATE_SUB).decode(parts, "raw");

        assertEquals(ChatEvent.CHOCOLATE, main.eventType());
        ChocolateEvent subEvent = assertInstanceOf(ChocolateEvent.class, sub);
        assertEquals(ChatEvent.CHOCOLATE_SUB, subEvent.eventType());
        assertEquals("user1", subEvent.senderId());
        assertEquals(3, subEvent.count());
    }

    @Test
    void unknownServiceCode_isDeliveredOnNoneType() {
        EventEmitter emitter = new EventEmitter();
        MessageDispatcher dispatcher = new MessageDispatcher(DECODERS, directExecutor(), emitter);

        AtomicReference<BaseEvent> received = new AtomicReference<>();
        emitter.on(ChatEvent.NONE_TYPE, (BaseEvent e) -> received.set(e));

        // 헤더 형식: ESC + TAB + 서비스 코드(4) + 길이(6) + 접미사(2)
        String header = SOOPConstants.ESC + "9999" + "000010" + "00";
        dispatcher.dispatchMessage(header + SOOPConstants.F + "7" + SOOPConstants.F);

        // 기본 팩토리는 NONE_TYPE에 NoneTypeDecoder를 등록하므로 UnknownEvent가 아닌 NoneTypeEvent가 온다.
        NoneTypeEvent event = assertInstanceOf(NoneTypeEvent.class, received.get());
        assertEquals(ChatEvent.NONE_TYPE, event.eventType());
        assertEquals(7, event.value());
    }

    private static ExecutorService directExecutor() {
        return new AbstractExecutorService() {
            @Override
            public void shutdown() {}

            @Override
            public List<Runnable> shutdownNow() {
                return List.of();
            }

            @Override
            public boolean isShutdown() {
                return false;
            }

            @Override
            public boolean isTerminated() {
                return false;
            }

            @Override
            public boolean awaitTermination(long timeout, TimeUnit unit) {
                return true;
            }

            @Override
            public void execute(Runnable command) {
                command.run();
            }
        };
    }
}
