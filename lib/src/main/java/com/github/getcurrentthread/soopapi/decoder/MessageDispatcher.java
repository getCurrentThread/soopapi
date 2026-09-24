package com.github.getcurrentthread.soopapi.decoder;

import java.util.Map;
import java.util.concurrent.Executor;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;

import com.github.getcurrentthread.soopapi.constant.SOOPConstants;
import com.github.getcurrentthread.soopapi.decoder.message.IMessageDecoder;
import com.github.getcurrentthread.soopapi.event.ChatEvent;
import com.github.getcurrentthread.soopapi.event.EventEmitter;
import com.github.getcurrentthread.soopapi.event.model.BaseEvent;
import com.github.getcurrentthread.soopapi.event.model.JoinChannelEvent;
import com.github.getcurrentthread.soopapi.event.model.RawEvent;
import com.github.getcurrentthread.soopapi.event.model.UnknownEvent;
import com.github.getcurrentthread.soopapi.util.SOOPChatUtils;

/**
 * 수신 패킷을 디코딩해 이벤트로 전달합니다.
 *
 * <p>디코딩과 emit은 생성자로 받은 {@link Executor}에서 실행됩니다. 스트림 단위 {@link
 * com.github.getcurrentthread.soopapi.util.SerialExecutor}를 넘기면 이벤트가 도착 순서대로 하나씩 전달됩니다.
 */
public class MessageDispatcher {
    private static final Logger LOGGER = Logger.getLogger(MessageDispatcher.class.getName());

    private final Map<ChatEvent, IMessageDecoder> messageDecoders;
    private final Executor messageProcessor;
    private final EventEmitter eventEmitter;
    private volatile boolean active = true;
    private volatile Consumer<JoinChannelEvent> joinChannelHook;

    public MessageDispatcher(
            Map<ChatEvent, IMessageDecoder> messageDecoders,
            Executor messageProcessor,
            EventEmitter eventEmitter) {
        this.messageDecoders = messageDecoders;
        this.messageProcessor = messageProcessor;
        this.eventEmitter = eventEmitter;
    }

    /**
     * {@link ChatEvent#JOIN_CHANNEL}을 사용자 리스너보다 먼저 받는 훅을 등록합니다. 사용자 리스너가 없어도 호출됩니다. 연결 단위 후속 처리에
     * 씁니다. 인증 연결의 ENTER_INFO는 이 훅이 아니라 WebSocketManager가 수신 스레드에서 보냅니다.
     */
    public void setJoinChannelHook(Consumer<JoinChannelEvent> hook) {
        this.joinChannelHook = hook;
    }

    /** 이후 들어오거나 아직 처리되지 않은 패킷을 모두 버립니다. 닫힌 연결의 늦은 패킷이 전달되지 않게 합니다. */
    public void deactivate() {
        active = false;
    }

    public void dispatchMessage(String message) {
        if (message == null || message.isEmpty() || !active) {
            return;
        }
        messageProcessor.execute(
                () -> {
                    if (active) {
                        process(message);
                    }
                });
    }

    private void process(String message) {
        try {
            if (eventEmitter.hasListeners(ChatEvent.RAW)) {
                eventEmitter.emit(
                        ChatEvent.RAW,
                        new RawEvent(ChatEvent.RAW, message, System.currentTimeMillis()));
            }

            int firstSep = message.indexOf(SOOPConstants.F_CHAR);
            if (firstSep < 0) {
                return;
            }

            int serviceCode = SOOPChatUtils.parseServiceCode(message, 0, firstSep);
            if (serviceCode < 0) {
                LOGGER.fine(() -> "Dropping packet with malformed header: " + truncate(message));
                return;
            }
            ChatEvent known = ChatEvent.fromCode(serviceCode);
            IMessageDecoder decoder = messageDecoders.get(known);
            // 디코더가 없는 코드는 모르는 코드와 똑같이 NONE_TYPE으로 보낸다.
            // 알려진 이벤트의 타입 지정 리스너가 UnknownEvent를 받지 않게 한다.
            ChatEvent chatEvent = decoder != null ? known : ChatEvent.NONE_TYPE;

            Consumer<JoinChannelEvent> hook =
                    chatEvent == ChatEvent.JOIN_CHANNEL ? joinChannelHook : null;
            if (hook == null && !eventEmitter.hasListeners(chatEvent)) {
                return;
            }

            BaseEvent event;
            if (decoder != null) {
                event = decoder.decode(SOOPChatUtils.splitFields(message, firstSep + 1), message);
            } else {
                // NONE_TYPE으로 합쳐지므로 헤더에서 읽은 원래 코드를 함께 싣는다.
                event =
                        new UnknownEvent(
                                serviceCode,
                                message,
                                ChatEvent.NONE_TYPE,
                                message,
                                System.currentTimeMillis());
            }

            if (event != null) {
                if (hook != null && event instanceof JoinChannelEvent join) {
                    runHook(hook, join);
                }
                eventEmitter.emit(chatEvent, event);
            } else {
                LOGGER.fine(() -> "Decoder for " + chatEvent + " skipped: " + truncate(message));
            }
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "Error processing message: " + truncate(message), e);
        }
    }

    private static void runHook(Consumer<JoinChannelEvent> hook, JoinChannelEvent event) {
        try {
            hook.accept(event);
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "JOIN_CHANNEL hook failed", e);
        }
    }

    private static String truncate(String message) {
        return message.length() > 200 ? message.substring(0, 200) + "..." : message;
    }
}
