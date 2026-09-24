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
import com.github.getcurrentthread.soopapi.util.SerialExecutor;

/**
 * WebSocket 하나에 묶인 수신 리스너. 수신 프레임을 메시지 단위로 모아 {@link MessageDispatcher}에 넘기고, 연결 수명주기는 {@link
 * Callbacks}로 알립니다.
 *
 * <ul>
 *   <li>콜백 안의 예외는 여기서 격리합니다. 예외가 JDK까지 전파되면 연결이 {@code onError}로 끊기기 때문입니다.
 *   <li>{@link #detach()} 뒤에는 데이터와 알림을 버리되, 닫힘 응답을 읽을 수 있도록 다음 프레임 요청은 계속합니다.
 *   <li>lane의 대기 태스크가 {@link #HIGH_WATER}를 넘으면 다음 프레임 요청을 멈추고, {@link #LOW_WATER} 이하로 내려오면 다시
 *       요청합니다.
 * </ul>
 */
public final class WebSocketListener implements WebSocket.Listener {
    private static final Logger LOGGER = Logger.getLogger(WebSocketListener.class.getName());
    static final int DEFAULT_BUFFER_SIZE = 16384;
    static final int HIGH_WATER = 10_000;
    static final int LOW_WATER = 1_000;

    /** 소켓 수명주기 알림. JDK 수신 스레드에서 호출됩니다. */
    interface Callbacks {
        /** 프레임을 하나 받았을 때. */
        void onInbound();

        /** 상대가 닫았을 때. Close 프레임 없이 끊기면 {@code statusCode}는 1006입니다. */
        void onClosed(int statusCode, String reason);

        /** 전송 계층 오류로 소켓이 끊겼을 때. */
        void onFailed(Throwable error);
    }

    private final MessageDispatcher messageDispatcher;
    private final SerialExecutor lane;
    private final Callbacks callbacks;
    private final StringBuilder textBuffer = new StringBuilder(DEFAULT_BUFFER_SIZE);
    private ByteArrayOutputStream binaryBuffer = new ByteArrayOutputStream(DEFAULT_BUFFER_SIZE);
    private volatile boolean detached;

    /**
     * @param lane 백프레셔 기준이 되는 lane. {@code null}이면 백프레셔를 걸지 않습니다.
     */
    WebSocketListener(
            MessageDispatcher messageDispatcher, SerialExecutor lane, Callbacks callbacks) {
        this.messageDispatcher = Objects.requireNonNull(messageDispatcher, "messageDispatcher");
        this.lane = lane;
        this.callbacks = Objects.requireNonNull(callbacks, "callbacks");
    }

    /** 이후 수신 데이터와 수명주기 알림을 모두 버립니다. */
    void detach() {
        detached = true;
    }

    @Override
    public void onOpen(WebSocket webSocket) {
        webSocket.request(1);
    }

    @Override
    public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
        boolean requestNext = true;
        try {
            if (detached) {
                textBuffer.setLength(0);
                return null;
            }
            callbacks.onInbound();
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
            requestNext = !pauseIfBacklogged(webSocket);
        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, "Error processing text message", e);
            resetTextBuffer();
        } finally {
            if (requestNext) {
                webSocket.request(1);
            }
        }
        return null;
    }

    @Override
    public CompletionStage<?> onBinary(WebSocket webSocket, ByteBuffer data, boolean last) {
        boolean requestNext = true;
        try {
            if (detached) {
                binaryBuffer.reset();
                return null;
            }
            callbacks.onInbound();
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
            requestNext = !pauseIfBacklogged(webSocket);
        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, "Error processing binary message", e);
            resetBinaryBuffer();
        } finally {
            if (requestNext) {
                webSocket.request(1);
            }
        }
        return null;
    }

    @Override
    public CompletionStage<?> onPing(WebSocket webSocket, ByteBuffer message) {
        return onControlFrame(webSocket);
    }

    @Override
    public CompletionStage<?> onPong(WebSocket webSocket, ByteBuffer message) {
        return onControlFrame(webSocket);
    }

    private CompletionStage<?> onControlFrame(WebSocket webSocket) {
        try {
            if (!detached) {
                callbacks.onInbound();
            }
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "Error handling control frame", e);
        } finally {
            webSocket.request(1);
        }
        return null;
    }

    @Override
    public void onError(WebSocket webSocket, Throwable error) {
        if (detached) {
            return;
        }
        try {
            callbacks.onFailed(error);
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "Error handling WebSocket failure", e);
        }
    }

    @Override
    public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
        if (detached) {
            return null;
        }
        try {
            callbacks.onClosed(statusCode, reason);
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "Error handling WebSocket close", e);
        }
        return null;
    }

    /** lane이 밀려 있으면 요청을 보류하고, 풀리면 다시 요청하도록 예약한다. 보류했으면 true. */
    private boolean pauseIfBacklogged(WebSocket webSocket) {
        if (lane == null || lane.pending() <= HIGH_WATER) {
            return false;
        }
        LOGGER.fine(() -> "Lane backlog " + lane.pending() + "; pausing reads");
        lane.whenPendingAtMost(LOW_WATER, () -> webSocket.request(1));
        return true;
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
