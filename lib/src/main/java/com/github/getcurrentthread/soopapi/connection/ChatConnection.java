package com.github.getcurrentthread.soopapi.connection;

import java.util.concurrent.CompletableFuture;

import com.github.getcurrentthread.soopapi.event.model.DisconnectedEvent;
import com.github.getcurrentthread.soopapi.exception.ConnectionException;
import com.github.getcurrentthread.soopapi.model.ConnectionStatus;

/**
 * 채팅 서버와의 연결 하나. {@link com.github.getcurrentthread.soopapi.client.SOOPChatClient}가 세션마다 만들어 쓰고,
 * 끝나면 버립니다.
 *
 * <p>구현은 모든 이벤트를 생성 시 받은 lane에서 emit해야 하며, 반환하는 future는 lane 밖에서, lock을 쥐지 않은 채 완료해야 합니다.
 */
public interface ChatConnection {

    /**
     * 연결을 시작합니다. 두 번째 호출부터는 첫 호출의 결과를 따릅니다.
     *
     * @return 연결이 수립되면 완료되고, 실패하면 예외로 완료되는 future
     */
    CompletableFuture<Void> connect();

    /**
     * 채널에 들어갔음을(서버가 JOIN에 응답했음을) 알리는 future. 호출마다 새 future를 돌려줍니다.
     *
     * <ul>
     *   <li>현재 소켓이 채널에 들어가 있으면 이미 완료된 future
     *   <li>연결 중이거나 재연결을 기다리는 중이면({@link #connect()} 전 포함) 다음 JOIN 응답에서 완료
     *   <li>그 전에 연결이 끝나면 {@link ConnectionException}으로 예외 완료. 방송 정보 조회 실패, {@link #close()}, 서버의 연결
     *       종료, 재시도 소진 등 끝나는 모든 경로가 해당하며, 끝난 뒤의 호출도 곧바로 실패합니다. 반대로 연결이 끝나지 않았으면 실패해서는 안 됩니다. 클라이언트는
     *       이 실패를 연결이 끝났다는 신호로 보고 세션을 정리합니다.
     * </ul>
     *
     * <p>연결 수명 내내 남는 future({@link #terminated()} 등)에 호출마다 의존 작업을 붙여서는 안 됩니다.
     */
    CompletableFuture<Void> ready();

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
