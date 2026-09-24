package com.github.getcurrentthread.soopapi.connection;

import com.github.getcurrentthread.soopapi.config.SOOPChatConfig;
import com.github.getcurrentthread.soopapi.event.EventEmitter;
import com.github.getcurrentthread.soopapi.util.SerialExecutor;

/**
 * {@link ChatConnection}과 이벤트 lane을 만듭니다. 기본 구현은 {@link ConnectionManager#getInstance()}입니다.
 *
 * <p>테스트나 고급 사용 시 {@link
 * com.github.getcurrentthread.soopapi.client.SOOPChatClient#SOOPChatClient(SOOPChatConfig,
 * ChatConnectionFactory)}로 주입할 수 있습니다.
 */
public interface ChatConnectionFactory {

    /**
     * 새 연결을 만듭니다. 연결은 아직 시작되지 않은 상태입니다.
     *
     * @param lane 이 연결의 모든 이벤트를 전달할 lane. 클라이언트가 평생 하나를 씁니다.
     */
    ChatConnection createConnection(
            SOOPChatConfig config, EventEmitter emitter, SerialExecutor lane);

    /** 클라이언트 하나가 평생 쓸 이벤트 lane을 만듭니다. */
    SerialExecutor newLane();
}
