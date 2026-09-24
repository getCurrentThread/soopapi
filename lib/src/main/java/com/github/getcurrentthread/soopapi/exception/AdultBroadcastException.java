package com.github.getcurrentthread.soopapi.exception;

/**
 * 19금(성인) 방송인데 그 방송을 볼 수 있는 로그인으로 요청하지 않았을 때 발생하는 예외입니다. 익명 요청이거나, 로그인했더라도 연령 인증이 없거나 로그인이 만료된
 * 경우입니다. 서버가 채팅 접속 정보를 주지 않으므로 연결할 수 없습니다.
 */
public class AdultBroadcastException extends AuthenticationException {

    /**
     * 지정된 메시지로 새 AdultBroadcastException을 구성합니다.
     *
     * @param message 예외 메시지
     */
    public AdultBroadcastException(String message) {
        super(message);
    }

    /**
     * 지정된 메시지와 원인으로 새 AdultBroadcastException을 구성합니다.
     *
     * @param message 예외 메시지
     * @param cause 원인 (null 허용)
     */
    public AdultBroadcastException(String message, Throwable cause) {
        super(message, cause);
    }
}
