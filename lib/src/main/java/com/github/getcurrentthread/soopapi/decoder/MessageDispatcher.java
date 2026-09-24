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
            ChatEvent chatEvent = ChatEvent.fromCode(serviceCode);

            Consumer<JoinChannelEvent> hook =
                    chatEvent == ChatEvent.JOIN_CHANNEL ? joinChannelHook : null;
            if (hook == null && !eventEmitter.hasListeners(chatEvent)) {
                return;
            }

            IMessageDecoder decoder = messageDecoders.get(chatEvent);
            String[] messageParts = SOOPChatUtils.splitFields(message, firstSep + 1);

            BaseEvent event;
            if (decoder != null) {
                event = decoder.decode(messageParts, message);
            } else {
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
