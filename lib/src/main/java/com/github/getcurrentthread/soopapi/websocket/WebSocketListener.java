package com.github.getcurrentthread.soopapi.websocket;

import java.io.ByteArrayOutputStream;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.concurrent.CompletionStage;
import java.util.logging.Level;
import java.util.logging.Logger;

import com.github.getcurrentthread.soopapi.decoder.MessageDispatcher;
import com.github.getcurrentthread.soopapi.event.ChatEvent;
import com.github.getcurrentthread.soopapi.event.EventEmitter;
import com.github.getcurrentthread.soopapi.event.model.DisconnectedEvent;

/**
 * 수신 프레임을 메시지 단위로 모아 {@link MessageDispatcher}에 넘깁니다.
 *
 * <p>콜백 안의 예외는 여기서 격리합니다. 예외가 JDK까지 전파되면 연결이 {@code onError}로 끊기기 때문입니다. 다음 프레임 요청({@code
 * request(1)})은 항상 {@code finally}에서 보냅니다.
 */
public class WebSocketListener implements WebSocket.Listener {
    private static final Logger LOGGER = Logger.getLogger(WebSocketListener.class.getName());
    static final int DEFAULT_BUFFER_SIZE = 16384;
    private final MessageDispatcher messageDispatcher;
    private final EventEmitter eventEmitter;
    private final StringBuilder textBuffer = new StringBuilder(DEFAULT_BUFFER_SIZE);
    private ByteArrayOutputStream binaryBuffer = new ByteArrayOutputStream(DEFAULT_BUFFER_SIZE);

    public WebSocketListener(MessageDispatcher messageDispatcher, EventEmitter eventEmitter) {
        this.messageDispatcher = Objects.requireNonNull(messageDispatcher, "messageDispatcher");
        this.eventEmitter = Objects.requireNonNull(eventEmitter, "eventEmitter");
    }

    @Override
    public void onOpen(WebSocket webSocket) {
        LOGGER.fine("WebSocket connection opened");
        webSocket.request(1);
    }

    @Override
    public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
        try {
            if (last && textBuffer.isEmpty()) {
                // 조각나지 않은 프레임은 버퍼를 거치지 않는다.
                messageDispatcher.dispatchMessage(data.toString());
            } else {
                textBuffer.append(data);
                if (last) {
                    String message = textBuffer.toString();
                    resetTextBuffer();
                    messageDispatcher.dispatchMessage(message);
                }
            }
        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, "Error processing text message", e);
            resetTextBuffer();
        } finally {
            webSocket.request(1);
        }
        return null;
    }

    @Override
    public CompletionStage<?> onBinary(WebSocket webSocket, ByteBuffer data, boolean last) {
        try {
            if (last && binaryBuffer.size() == 0) {
                messageDispatcher.dispatchMessage(decodeUtf8(data));
            } else {
                appendBinary(data);
                if (last) {
                    String message = binaryBuffer.toString(StandardCharsets.UTF_8);
                    resetBinaryBuffer();
                    messageDispatcher.dispatchMessage(message);
                }
            }
        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, "Error processing binary message", e);
            resetBinaryBuffer();
        } finally {
            webSocket.request(1);
        }
        return null;
    }

    @Override
    public CompletionStage<?> onPing(WebSocket webSocket, ByteBuffer message) {
        webSocket.request(1);
        return null;
    }

    @Override
    public CompletionStage<?> onPong(WebSocket webSocket, ByteBuffer message) {
        webSocket.request(1);
        return null;
    }

    @Override
    public void onError(WebSocket webSocket, Throwable error) {
        LOGGER.log(Level.WARNING, "WebSocket error", error);
        String errorMessage =
                error.getMessage() != null ? error.getMessage() : error.getClass().getName();
        emitDisconnected(
                new DisconnectedEvent(
                        -1,
                        errorMessage,
                        true,
                        ChatEvent.DISCONNECTED,
                        "",
                        System.currentTimeMillis()));
    }

    @Override
    public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
        LOGGER.info("WebSocket closed: statusCode=" + statusCode + ", reason=" + reason);
        emitDisconnected(
                new DisconnectedEvent(
                        statusCode,
                        reason,
                        false,
                        ChatEvent.DISCONNECTED,
                        "",
                        System.currentTimeMillis()));
        return null;
    }

    private void emitDisconnected(DisconnectedEvent event) {
        try {
            eventEmitter.emit(ChatEvent.DISCONNECTED, event);
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "Error emitting DISCONNECTED", e);
        }
    }

    /** JDK가 넘기는 버퍼는 배열 오프셋이 0이 아닌 slice일 수 있으므로 {@code arrayOffset()+position()}부터 읽는다. */
    private static String decodeUtf8(ByteBuffer data) {
        if (data.hasArray()) {
            String s =
                    new String(
                            data.array(),
                            data.arrayOffset() + data.position(),
                            data.remaining(),
                            StandardCharsets.UTF_8);
            data.position(data.limit());
            return s;
        }
        return StandardCharsets.UTF_8.decode(data).toString();
    }

    private void appendBinary(ByteBuffer data) {
        if (data.hasArray()) {
            binaryBuffer.write(
                    data.array(), data.arrayOffset() + data.position(), data.remaining());
            data.position(data.limit());
        } else {
            byte[] bytes = new byte[data.remaining()];
            data.get(bytes);
            binaryBuffer.write(bytes, 0, bytes.length);
        }
    }

    private void resetTextBuffer() {
        textBuffer.setLength(0);
        if (textBuffer.capacity() > DEFAULT_BUFFER_SIZE * 4) {
            textBuffer.trimToSize();
            textBuffer.ensureCapacity(DEFAULT_BUFFER_SIZE);
        }
    }

    private void resetBinaryBuffer() {
        if (binaryBuffer.size() > DEFAULT_BUFFER_SIZE * 4) {
            binaryBuffer = new ByteArrayOutputStream(DEFAULT_BUFFER_SIZE);
        } else {
            binaryBuffer.reset();
        }
    }
}
