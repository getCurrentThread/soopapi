package com.github.getcurrentthread.soopapi.connection;

import java.util.concurrent.CompletableFuture;

import com.github.getcurrentthread.soopapi.event.model.DisconnectedEvent;
import com.github.getcurrentthread.soopapi.model.ConnectionStatus;

/**
 * 채팅 서버와의 연결 하나. {@link com.github.getcurrentthread.soopapi.client.SOOPChatClient}가 세션마다 만들어 쓰고,
 * 끝나면 버립니다.
 *
 * <p>구현은 모든 이벤트를 생성 시 받은 lane에서 emit해야 하며, 반환하는 future는 lane 밖에서 완료해야 합니다.
 */
public interface ChatConnection {

    /**
     * 연결을 시작합니다. 두 번째 호출부터는 첫 호출의 결과를 따릅니다.
     *
     * @return 연결이 수립되면 완료되고, 실패하면 예외로 완료되는 future
     */
    CompletableFuture<Void> connect();

    /** 현재 연결을 버리고 다시 연결합니다. 수립되면 완료됩니다. */
    CompletableFuture<Void> reconnect();

    /**
     * 연결이 끝났음을 알리는 future. {@link #connect()}의 결과가 정해진 뒤, 모든 경로에서 정확히 한 번 완료됩니다.
     *
     * <ul>
     *   <li>서버가 연결을 닫거나 {@link #close()}를 호출하면 {@link DisconnectedEvent}로 정상 완료
     *   <li>연결 실패나 재시도 소진이면 예외로 완료
     * </ul>
     */
    CompletableFuture<DisconnectedEvent> terminated();

    CompletableFuture<Void> sendChat(String message);

    CompletableFuture<Void> sendWhisper(String targetId, String message);

    CompletableFuture<ConnectionStatus> status();

    boolean isConnected();

    /** 연결을 닫습니다. 여러 번 호출해도 안전하며 블로킹하지 않습니다. */
    void close();
}
