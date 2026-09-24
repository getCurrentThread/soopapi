# 비공식 SOOP 채팅 API

[![CI](https://github.com/getCurrentThread/soopapi/actions/workflows/ci.yml/badge.svg)](https://github.com/getCurrentThread/soopapi/actions/workflows/ci.yml)
[![JitPack](https://jitpack.io/v/getCurrentThread/soopapi.svg)](https://jitpack.io/#getCurrentThread/soopapi)

이 프로젝트는 SOOP의 채팅 시스템과 상호 작용할 수 있는 비공식 Java 라이브러리입니다. 개발자들이 SOOP 채팅방에 연결하고, 메시지를 수신하며, 다양한 이벤트를 처리할 수 있도록 해줍니다.

## 주요 기능

- **이벤트 기반 아키텍처**: 타입 안전한 `on(event, handler)` 패턴으로 이벤트 구독
- **Sealed 이벤트 계층**: `ChatBaseEvent`, `DonationBaseEvent`, `SystemBaseEvent` 등 6개 카테고리로 분류된 이벤트 타입
- **92개 서버 이벤트 지원**: 채팅 메시지, 풍선, 이모티콘, 구독 등 모든 서버 이벤트를 Java Record로 디코딩(연결 상태 등 클라이언트 이벤트 5개 별도)
- **코드표 디코딩**: 사용자 등급·아이스 모드·퇴장 사유 등 원시 코드를 `UserLevel`·`ChatIceType`·`ChatQuitStatus`로 지연 디코딩(`senderLevel()`, `iceType()`, `quitStatus()`)
- **연결 상태 이벤트**: `DISCONNECTED`, `RECONNECTING`, `RECONNECTED` 이벤트로 연결 라이프사이클 추적
- **스트림별 순서 보장**: 이벤트는 스트림마다 도착 순서대로 한 번에 하나씩 전달(공유 가상 스레드 풀 위의 직렬 실행)
- **통합 API 클라이언트**: `SOOPClient` 파사드로 인증, 방송 정보, 채널 정보, 채팅을 통합 관리
- **다중 채팅 연결**: bid 기준 dedup된 `add`/`remove`/`get` API와 `(streamerId, event)`를 함께 받는 글로벌 이벤트 리스너 지원
- **채팅 전송 지원**: `sendChat()` / `sendWhisper()` 메서드로 채팅·귓말 전송, `ready()`로 채널 입장을 기다린 뒤 전송
- **익명(읽기 전용) 연결**: 인증 없이 채팅 수신 가능
- 네트워크 장애 시 backoff 자동 재연결(서버가 연결을 닫으면 세션 종료)
- 이벤트 리스너 에러 핸들링

## 필요 조건

- Java 25 이상
- Gradle 9.3.1 이상

## 설치

### Gradle (JitPack)

```groovy
repositories {
    maven { url 'https://jitpack.io' }
}

dependencies {
    implementation 'com.github.getCurrentThread:soopapi:v0.14.0'
}
```

### 소스에서 빌드

1. 저장소 복제:

   ```
   git clone https://github.com/getCurrentThread/soopapi.git
   ```

2. 프로젝트 빌드:

   ```
   cd soopapi
   ./gradlew build
   ```

3. 빌드된 JAR 파일을 프로젝트의 종속성에 포함시킵니다.

## 사용 방법

### 통합 클라이언트 (SOOPClient)

```java
import com.github.getcurrentthread.soopapi.SOOPClient;
import com.github.getcurrentthread.soopapi.api.model.*;
import com.github.getcurrentthread.soopapi.client.SOOPChatClient;
import com.github.getcurrentthread.soopapi.event.ChatEvent;
import com.github.getcurrentthread.soopapi.event.model.*;

public class Example {
    public static void main(String[] args) throws Exception {
        try (SOOPClient client = new SOOPClient()) {
            // 방송 정보 조회
            LiveDetail detail = client.live().detail("streamerId").join();
            System.out.println("방송 제목: " + detail.title());

            // 채널 정보 조회
            StationInfo station = client.channel().station("streamerId").join();
            System.out.println("스테이션: " + station.stationName());

            // 글로벌 리스너 먼저 등록 — 이후 추가되는 스트림에 자동 attach됨
            client.on(ChatEvent.CHAT_MESSAGE, (String bid, ChatMessageEvent e) -> {
                System.out.println("[" + bid + "] " + e.senderNickname() + ": " + e.message());
            });

            client.on(ChatEvent.SEND_BALLOON, (String bid, SendBalloonEvent e) -> {
                System.out.println("[" + bid + "] " + e.senderNickname()
                        + "님이 풍선 " + e.count() + "개 선물!");
            });

            // add() 호출 즉시 비동기 연결이 시작됩니다 (별도 connectToChat() 불필요)
            client.add("streamerId");

            // 등록된 모든 세션이 끝날 때까지 블로킹
            client.connectAll().join();
        }
    }
}
```

### 다중 채팅 연결

`SOOPClient`는 여러 스트리머에 대한 채팅 연결을 한 곳에서 관리합니다. **`add()` 호출 즉시 비동기 연결이 시작되며**, 사용자는 별도의 `connectToChat()`을 부르지 않아도 됩니다. `add()`는 bid 기준으로 dedup되며, `on()`으로 등록한 글로벌 리스너는 **현재 등록된 모든 스트림과 이후 추가되는 스트림**에 자동으로 attach됩니다. 핸들러는 어떤 스트리머에서 발생한 이벤트인지를 첫 번째 인자로 받습니다.

```java
try (SOOPClient client = new SOOPClient()) {
    // 글로벌 리스너 먼저 등록 — 이후 add()되는 스트림에도 자동 적용
    client.on(ChatEvent.CHAT_MESSAGE, (String bid, ChatMessageEvent e) -> {
        System.out.println("[" + bid + "] " + e.senderNickname() + ": " + e.message());
    });

    // 연결 실패/끊김 추적
    client.on(ChatEvent.DISCONNECTED, (String bid, DisconnectedEvent e) -> {
        if (e.causedByError()) {
            System.err.println("[" + bid + "] 연결 오류로 끊김: " + e.reason());
        }
    });

    // 등록 즉시 자동 연결됨 — 호출 순서/타이밍은 자유
    client.add("streamerA");
    client.add("streamerB");
    client.add("streamerA"); // dedup: 기존 인스턴스 반환, no-op

    // 런타임 중 추가/제거 자유
    Thread.sleep(5_000);
    client.add("streamerC");            // 자동 연결
    client.reconnect("streamerA");      // 강제 재연결 (tear-down + 새 연결)
    client.remove("streamerB");         // disconnect + 등록 해제

    // 등록된 모든 세션이 끝날 때까지 대기
    client.connectAll().join();
}
```

> **권장 패턴**: 글로벌 리스너(`client.on(...)`)를 `add()`보다 먼저 등록하면 초기 이벤트(LOGIN/JOIN_CHANNEL 등) 누락을 방지할 수 있습니다.

개별 클라이언트 핸들 접근:

```java
SOOPChatClient a = client.get("streamerA");        // 없으면 null
Set<String> ids = client.streamerIds();             // 등록된 bid 스냅샷
Collection<SOOPChatClient> all = client.clients();  // 등록된 클라이언트 스냅샷
```

### 직접 연결

```java
import com.github.getcurrentthread.soopapi.client.SOOPChatClient;
import com.github.getcurrentthread.soopapi.config.SOOPChatConfig;
import com.github.getcurrentthread.soopapi.event.ChatEvent;
import com.github.getcurrentthread.soopapi.event.model.ChatMessageEvent;

public class DirectExample {
    public static void main(String[] args) throws Exception {
        SOOPChatConfig config = new SOOPChatConfig.Builder()
                .bid("streamerId")
                .build();

        SOOPChatClient client = new SOOPChatClient(config);

        client.on(ChatEvent.CHAT_MESSAGE, (ChatMessageEvent e) -> {
            System.out.println(e.senderNickname() + ": " + e.message());
        });

        // connectAndAwait()는 세션이 끝날 때까지 블로킹됩니다
        client.connectAndAwait();
    }
}
```

> **참고**: `SOOPChatClient` 생성자는 네트워크 호출을 하지 않습니다. BNO가 설정되지 않은 경우 `connectToChat()` 호출 시 자동으로 해석됩니다.

### 익명(읽기 전용) 연결

`authCookie` 없이 연결하면 익명 모드로 동작합니다. 채팅 메시지와 이벤트를 수신할 수 있지만, `sendChat()`을 호출하면 `AuthenticationException`이 발생합니다.

```java
// 인증 없이 읽기 전용으로 연결
SOOPChatConfig config = new SOOPChatConfig.Builder()
        .bid("streamerId")
        .build();

SOOPChatClient client = new SOOPChatClient(config);

client.on(ChatEvent.CHAT_MESSAGE, (ChatMessageEvent e) -> {
    System.out.println(e.senderNickname() + ": " + e.message());
});

client.connectAndAwait(); // 채팅 수신 가능, 전송 불가
```

> **19금 방송**: 서버가 19금 방송의 채팅 접속 정보를 익명 요청에 주지 않으므로 익명으로는 들어갈 수 없습니다. 세션은 재시도 없이 `DISCONNECTED(causedByError=true)`로 끝나고, `connectToChat()`·`ready()`는 `AdultBroadcastException`을 원인으로 둔 `ConnectionException`으로 실패합니다. 연령 인증된 계정으로 로그인해 그 `authCookie`를 넘기면 방송 정보 조회(`detail()`)가 19금 방송의 채팅 접속 정보를 받고 채팅에도 연결됩니다(실제 계정으로 확인). 19금 방송을 여는 것은 이 로그인 쿠키이고 19금 확인 값은 결과를 바꾸지 않으므로, 라이브러리는 19금 확인을 사용자 대신 하지 않고 늘 `confirm_adult=false`로 요청합니다.
> 방송 목록의 `broad_grade`는 캐시된 값이라 지금의 19금 여부와 다를 수 있습니다. 조회 결과로 판단하세요.

### 인증 (채팅 전송 시 필수)

읽기 전용(이벤트 수신)은 인증 없이 사용할 수 있지만, `sendChat()`으로 채팅을 전송하려면 반드시 인증이 필요합니다.

```java
SOOPClient client = new SOOPClient();

// 1. 로그인 (실패하면 join()이 AuthenticationException을 원인으로 담은 CompletionException을 던짐)
AuthCookie cookie = client.auth().signIn("userId", "password").join();
System.out.println("로그인 성공");

// 2. 인증된 설정으로 채팅 클라이언트 생성
SOOPChatConfig config = new SOOPChatConfig.Builder()
        .bid("streamerId")
        .authCookie(cookie)
        .build();

SOOPChatClient chat = new SOOPChatClient(config);

chat.on(ChatEvent.CHAT_MESSAGE, (ChatMessageEvent e) -> {
    System.out.println(e.senderNickname() + ": " + e.message());
});

// 3. 연결 시작. 반환된 future는 세션이 끝날 때 완료되므로 여기서 기다리지 않습니다
chat.connectToChat();

// 4. 채널에 입장하면(ready) 전송합니다
chat.ready().thenRun(() -> {
    chat.sendChat("Hello!")
            .exceptionally(ex -> { System.err.println("전송 실패: " + ex); return null; });

    // 특정 사용자에게 귓말 전송 ("targetUser" = 받는 사람 로그인 ID, 닉네임/(n) 형태 아님)
    chat.sendWhisper("targetUser", "안녕하세요");
}).exceptionally(ex -> { System.err.println("채널 입장 전에 세션이 끝남: " + ex); return null; });
```

- `ready()`는 `connectToChat()` 뒤에 부릅니다. 세션이 없으면 `IllegalStateException`으로 실패합니다. 완료 콜백은 대개 수신 스레드에서 실행되므로 오래 걸리는 작업은 `thenRunAsync`로 넘깁니다.
- 인증 연결의 입장 정보(ENTER_INFO)는 `ready()`가 완료되기 전에 송신 순서에 들어가므로, 완료되자마자 보낸 메시지도 그 뒤에 나갑니다.
- `JOIN_CHANNEL` 리스너에서 보내도 됩니다. 다만 `JOIN_CHANNEL`은 재연결로 채널에 다시 들어갈 때마다 다시 발생하므로, 한 번만 보내려면 `once()`를 씁니다.
- 메시지가 `null`·공백이거나 제어 문자 `U+000C`·`U+001B`를 포함하면 `IllegalArgumentException`으로 실패합니다.
- 리스너 안에서 `sendChat(..).join()`이나 `ready().join()`처럼 기다리는 것은 안전합니다. 하지만 **세션 future**(`connectToChat()`, `connectAndAwait()`, `forceReconnect()`)를 기다리면 이벤트 전달이 멈춰 교착 상태가 됩니다.

### 연결 상태 이벤트

연결 라이프사이클을 추적할 수 있는 시스템 이벤트가 제공됩니다.

```java
import com.github.getcurrentthread.soopapi.event.model.*;

// 세션 종료 감지 (세션마다 정확히 한 번)
client.on(ChatEvent.DISCONNECTED, (DisconnectedEvent e) -> {
    System.out.println("연결 해제: code=" + e.statusCode()
            + ", reason=" + e.reason()
            + ", error=" + e.causedByError());
});

// 직접 끝낸 경우가 아니면 5초 뒤 새 세션 시작 (첫 연결 실패도 포함되므로 방송이 꺼져 있으면 5초마다 다시 시도)
client.on(ChatEvent.DISCONNECTED, (DisconnectedEvent e) -> {
    if (e.isClientInitiated()) {
        return; // disconnect()/close()로 직접 끝낸 경우
    }
    CompletableFuture.runAsync(
            client::connectToChat, CompletableFuture.delayedExecutor(5, TimeUnit.SECONDS));
});

// 재연결 시도 감지
client.on(ChatEvent.RECONNECTING, (ReconnectingEvent e) -> {
    System.out.println("재연결 시도 " + e.attemptNumber()
            + "/" + e.maxAttempts()
            + " (" + e.delayMs() + "ms 후)");
});

// 재연결 완료 감지
client.on(ChatEvent.RECONNECTED, (ReconnectedEvent e) -> {
    System.out.println("재연결 완료 (총 " + e.totalAttempts() + "회 시도)");
});
```

### 에러 핸들링

이벤트 리스너에서 발생하는 예외를 중앙에서 처리할 수 있습니다.

```java
import com.github.getcurrentthread.soopapi.exception.EventEmitterException;

client.getEventEmitter().setErrorHandler((EventEmitterException ex) -> {
    System.err.println("이벤트 처리 중 오류: " + ex.getChatEvent());
    ex.printStackTrace();
});
```

### 고급 설정

`SOOPChatConfig.Builder`를 통해 연결 동작을 세밀하게 제어할 수 있습니다.

```java
SOOPChatConfig config = new SOOPChatConfig.Builder()
        .bid("streamerId")
        .bno("12345")                                  // 방송 번호 (생략 시 자동 해석)
        .connectionTimeout(Duration.ofSeconds(15))     // 연결·송신 타임아웃, 0보다 커야 함 (기본: 30초)
        .maxRetryAttempts(3)                           // 자동 재연결 최대 재시도 횟수 (기본: 5)
        .pingIntervalSeconds(30)                       // 핑 전송 간격 (기본: 60초)
        .authCookie(cookie)                            // 인증 쿠키 (생략 시 읽기 전용)
        .build();
```

> `initialPacketDelayMs`는 아무 효과가 없어 deprecated(제거 예정)되었습니다. `SOOPClientConfig`의 `connectionTimeout`은 REST API(`auth()`/`live()`/`channel()`)에만 적용되고, `maxRetryAttempts`는 `add(String)`으로 등록한 스트림의 재시도 한도가 됩니다.

## 연결 라이프사이클

`SOOPClient.add()`는 등록과 동시에 비동기 연결을 시작하므로 일반적으로 사용자가 직접 연결 메서드를 호출할 필요가 없습니다. 저수준 `SOOPChatClient`를 직접 사용하는 경우에만 아래 메서드를 사용합니다.

`connectToChat()`은 **세션**을 시작합니다. 반환된 `CompletableFuture`는 세션이 **끝날 때** 완료됩니다. `disconnect()`나 서버의 연결 종료로 끝나면 정상 완료, 연결 실패나 재시도 소진으로 끝나면 `ConnectionException`으로 예외 완료됩니다. 채널에 들어간 시점은 `ready()`로 기다리거나 `JOIN_CHANNEL` 이벤트·`isConnected()`로 확인하세요.

| 메서드 | 동작 |
|--------|------|
| `SOOPClient.add(streamerId)` | 등록 + **즉시 비동기 연결**. 이미 등록된 bid면 기존 인스턴스 반환(세션이 끝난 인스턴스면 새 세션 시작). |
| `SOOPClient.connectAll()` | 등록된 모든 세션이 끝날 때 완료되는 future. 끝난 세션은 새로 시작하며, 하나라도 실패로 끝나면 예외로 완료. |
| `SOOPClient.reconnect(streamerId)` | 방송 정보 조회부터 새 연결로 **강제 재연결**. 현재 상태/backoff 무시. 세션은 유지되며 `RECONNECTING`→`RECONNECTED`만 emit(`DISCONNECTED` 없음). 새 연결이 실패하면 세션이 `DISCONNECTED(causedByError=true)`로 끝남. |
| `SOOPClient.reconnectAll()` | 등록된 모든 스트림을 강제 재연결하고, 모든 세션이 끝날 때 완료되는 future 반환. |
| `SOOPClient.remove(streamerId)` | 세션 종료 + 등록 해제. 제거된 클라이언트는 `DISCONNECTED` 리스너가 재연결을 시도해도 다시 연결되지 않음. 글로벌 리스너는 먼저 떼어 내므로 마지막 `DISCONNECTED`는 클라이언트에 직접 등록한 리스너에만 전달. |
| `SOOPClient.close()` | 모든 클라이언트 종료 + HTTP 리소스 해제. `try-with-resources` 권장. |
| `SOOPChatClient.connectToChat()` | (저수준) 세션 시작. 진행 중인 세션이 있으면 같은 future 반환. |
| `SOOPChatClient.connectAndAwait()` | (저수준) `connectToChat().join()`의 편의 메서드 (블로킹). |
| `SOOPChatClient.ready()` | 현재 세션이 채널에 들어가면(서버가 JOIN에 응답하면) 완료되는 future. 이미 들어가 있으면 완료된 future, 연결 중·backoff 대기 중이면 다음 JOIN 응답에서 완료되며 `reconnect()`·`forceReconnect()`를 거쳐도 실패하지 않고 새 연결을 기다림. 그 전에 세션이 끝나면(`disconnect()`·`close()`·서버 종료·연결 실패·재시도 소진) `ConnectionException`, 세션이 없거나 `close()` 뒤면 `IllegalStateException`으로 실패. |
| `SOOPChatClient.disconnect()` | (저수준) 연결 중·재연결 대기 중을 포함해 어떤 상태에서도 세션 종료. 블로킹하지 않으며 `DISCONNECTED`는 비동기로 전달. 이후 새 세션 시작 가능. |
| `SOOPChatClient.close()` | (저수준) 세션 종료 후 클라이언트를 닫음. 이후 `connectToChat()`·`forceReconnect()`·`ready()`는 실패. |
| `SOOPChatClient.reconnect()` | (저수준) 방송 정보 재조회 없이 WebSocket만 다시 엶. 새 소켓이 채널에 입장하면 완료. 세션이 없으면 실패. |
| `SOOPChatClient.forceReconnect()` | (저수준) 방송 정보 조회부터 새 연결. 세션 유지, `RECONNECTING(1/1)`→`RECONNECTED` emit, `DISCONNECTED` 없음(새 연결이 실패하면 세션 종료). 세션이 없으면 새로 시작. |

### 끊김과 재연결

- **서버가 연결을 닫으면**(Close 프레임) 세션이 끝납니다. `DISCONNECTED`(`causedByError=false`)가 발생하며 자동으로 다시 연결하지 않습니다.
- **네트워크 오류·비정상 종료(1006)·송신/핑 실패·채널 입장 응답 없음**이면 backoff(2초부터 두 배씩, 최대 30초)로 자동 재연결하며 `RECONNECTING`→`RECONNECTED`가 발생합니다. `maxRetryAttempts`를 다 쓰면 `DISCONNECTED`(`causedByError=true`)와 함께 세션 future가 예외로 완료됩니다.
- **연결됨**(`isConnected()`, `ready()`, `RECONNECTED`)은 서버가 채널 입장(JOIN)에 응답한 시점입니다. 서버는 같은 클라이언트가 방금 나간 채널에 곧바로 다시 들어오는 요청을 몇 초간 무시할 수 있어, 응답이 올 때까지 입장 요청을 1초마다 다시 보냅니다. 그래서 `forceReconnect()` 직후 재입장까지 1~3초가 걸릴 수 있습니다.
- 재연결을 기다리는 동안 부른 `ready()`는 다음 입장에서 완료되고, 세션이 끝날 때만 실패합니다. 제한 시간을 두고 반복해서 부르기보다 받은 future 하나를 재사용하세요.

### 이벤트 전달 규칙

- 이벤트는 스트림마다 **도착 순서대로 한 번에 하나씩** 전달됩니다. 리스너가 느리면 그 스트림의 이벤트만 늦어집니다.
- `DISCONNECTED`는 세션마다 **정확히 한 번** 발생하고, 그 뒤로는 해당 세션의 이벤트가 오지 않습니다. 직접 끝낸 경우는 `isClientInitiated()`로 구분합니다.
- 리스너 안에서 세션 future(`connectToChat()`, `connectAndAwait()`, `forceReconnect()`)를 기다리면 교착 상태가 됩니다. 송신 결과(`sendChat(..).join()`), `reconnect().join()`, `ready().join()`은 기다려도 됩니다. `ready()`는 이벤트 전달 순서를 거치지 않고 대개 수신 스레드에서 `JOIN_CHANNEL` 리스너보다 먼저 완료되기 때문입니다. 다만 기다리는 동안 그 스트림의 이벤트 전달은 멈춥니다.
- `JOIN_CHANNEL`은 재연결로 채널에 다시 들어갈 때마다 다시 발생합니다.

## 이벤트 타입

`ChatEvent` 열거형으로 모든 이벤트를 구독할 수 있습니다. 각 이벤트는 타입 안전한 Java Record로 디코딩됩니다.

### 지원 이벤트 목록

아래 30개 이벤트는 서버 형식을 그대로 따르는 합성 패킷으로 모든 필드의 디코딩을 테스트합니다.

| 이벤트 | 코드 | Record 타입 | 주요 필드 |
|--------|------|-------------|-----------|
| `LOGIN` | 1 | `LoginEvent` | `userId`, `userFlag` (익명 시 userId="") |
| `JOIN_CHANNEL` | 2 | `JoinChannelEvent` | `chatNo`, `bjId`, `maxSubBjCount`, `userFlag` |
| `CHAT_USER` | 4 | `ChatUserEvent` | `type`, `userList` (id/nickname/flag 목록) |
| `CHAT_MESSAGE` | 5 | `ChatMessageEvent` | `message`, `senderId`, `senderNickname`, `senderFlag`, `subscriptionMonth`, `randomNicknameColor` |
| `SET_BJ_STAT` | 7 | `SetBjStatEvent` | (공통 필드만, 방송 연결 해제 시 수신) |
| `SET_DUMB` | 8 | `SetDumbEvent` | `userId`, `userNickname`, `dumbTime`(초), `dumbCount`, `adminId`, `adminType` |
| `SET_USER_FLAG` | 12 | `SetUserFlagEvent` | `userId`, `userNickname`, `oldFlag`, `newFlag` |
| `SET_SUB_BJ` | 13 | `SetSubBjEvent` | `userId`, `nickname`, `flag`, `hide` |
| `SET_NICKNAME` | 14 | `SetNicknameEvent` | `userId`, `newNickname`, `oldNickname`, `changeType`, `flag` |
| `SEND_BALLOON` | 18 | `SendBalloonEvent` | `bjId`, `senderId`, `senderNickname`, `count`, `fanOrder`, `fileName`, `isDefault`, `ttsData` |
| `ICE_MODE` | 19 | `IceModeEvent` | `iceMode` (1=활성화) |
| `ICE_MODE_EX` | 21 | `IceModeExEvent` | `iceMode`, `freezeType`, `balloonLimitCount`, `subscriptionLimitCount` |
| `BJ_STICKER_ITEM` | 36 | `BjStickerItemEvent` | `type` |
| `BAN_WORD` | 54 | `BanWordEvent` | `replaceWord` (대체어), `banWordList` (금지어 목록, `List<String>`) |
| `ADCON_EFFECT` | 87 | `AdconEffectEvent` | `bjId`, `senderId`, `senderNickname`, `adconCount`, `message`, `message2`, `urlImg`, `urlDefault` |
| `KICK_MSG_STATE` | 90 | `KickMsgStateEvent` | `chatNo`, `isHideKickMessage` |
| `FOLLOW_ITEM` | 91 | `FollowItemEvent` | `chatNo`, `recvId`, `sendId`, `sendNick`, `type` (신규 구독) |
| `FOLLOW_ITEM_EFFECT` | 93 | `FollowItemEffectEvent` | `bjId`, `sendId`, `sendNick`, `month` (연속 구독 개월 수) |
| `TRANSLATION_STATE` | 94 | `TranslationStateEvent` | `state` (1=번역 활성화) |
| `BJ_NOTICE` | 104 | `BjNoticeEvent` | `show` (1=표시), `message` (공지 내용) |
| `VIDEO_BALLOON` | 105 | `VideoBalloonEvent` | `bjId`, `userId`, `userNickname`, `balloonCount`, `fanOrder`, `isDefault`, `extraData` |
| `SEND_SUBSCRIPTION` | 108 | `SendSubscriptionEvent` | `senderId`, `senderNickname`, `receiverId`, `receiverNickname`, `itemType`, `itemCode`, `subscriptionType` |
| `OGQ_EMOTICON` | 109 | `OGQEmoticonEvent` | `chatNo`, `groupId`, `subId`, `version`, `senderId()`, `senderNickname()` (옛 이름 `userInfo`, `color`) |
| `EMOTICON_TICKET` | 110 | `EmoticonTicketEvent` | `value` (채널 입장 직후 수신, 보통 1) |
| `ITEM_DROPS` | 111 | `ItemDropsEvent` | `bjId`, `dropsName`, `dropsMsg`, `dropsImgUrl` |
| `OGQ_EMOTICON_GIFT` | 118 | `GiftOGQEmoticonEvent` | `senderId`, `senderNick`, `receivedId`, `receivedNick`, `ogqTitle`, `ogqImageUrl` |
| `MISSION` | 121 | `MissionEvent` | `data` (JSON Map: type, mission_status, title, key, uuid) |
| `MISSION_SETTLE` | 125 | `MissionSettleEvent` | `data` (JSON Map: chno, fanOrder, list, uuid) |
| `CHUSER_EXTEND` | 127 | `ChuserExtendEvent` | `userStatus` (구독자 fw/afw 상태 맵) |
| `SEND_QUICK_VIEW` | 45 | `QuickViewEvent` | `senderId`, `senderNickname`, `receiverId`, `receiverNickname`, `itemType` |

### 연결 상태 이벤트

| 이벤트 | 코드 | Record 타입 | 주요 필드 |
|--------|------|-------------|-----------|
| `DISCONNECTED` | -3 | `DisconnectedEvent` | `statusCode`, `reason`, `causedByError`, `isClientInitiated()` |
| `RECONNECTING` | -4 | `ReconnectingEvent` | `attemptNumber`, `maxAttempts`, `delayMs` |
| `RECONNECTED` | -5 | `ReconnectedEvent` | `totalAttempts` |

전체 이벤트(서버 이벤트 92개 + 클라이언트 이벤트 `RAW`·`DISCONNECTED`·`RECONNECTING`·`RECONNECTED`·`NONE_TYPE`)는 `ChatEvent.java`를, 모든 Record 필드 상세는 `llms-full.txt`를 참조하세요. 알 수 없는 서비스 코드의 패킷은 `NONE_TYPE` 리스너에 `UnknownEvent`로 전달되며, 원래 코드는 `code()`, 패킷 전체는 `raw()`(`originalMessage()`와 같음)로 확인합니다.

### 변경 사항 (호환성)

v0.14.0에서 올리는 경우 아래 변경을 확인하세요.

- `BanWordEvent.banWordList()`는 `String[]` 대신 `List<String>`을 반환합니다. 빈 토큰을 버리고 공백을 trim하지 않는 규칙은 같습니다.
- 이벤트의 목록·맵 필드는 수정할 수 없는 복사본입니다: `BanWordEvent.banWordList`, `ChatUserEvent.userList`, `AdminChatUserEvent.users`, `KickUserListEvent.kickedUsers`, `ChuserExtendEvent.userStatus`(바깥 맵과 안쪽 맵 모두). 수정하면 `UnsupportedOperationException`이 발생합니다. 생성자는 `null`을 빈 목록·맵으로 바꾸고 `null` 요소는 받지 않습니다. 같은 이벤트 객체가 모든 리스너에 전달되므로, 고쳐 쓰려면 `new ArrayList<>(e.userList())`처럼 복사합니다.
- 알 수 없는 서비스 코드는 `NONE_TYPE`에 `UnknownEvent`(`code()` = 원래 서비스 코드)로 전달됩니다. `NoneTypeEvent`와 `NoneTypeDecoder`는 제거되었습니다.
- `UnknownEvent`는 `SystemBaseEvent`가 아니라 `BaseEvent`를 직접 구현합니다. `NONE_TYPE` 리스너를 `SystemBaseEvent`로 받고 있었다면 `UnknownEvent`나 `BaseEvent`로 바꿉니다.
- 새 API: `SOOPChatClient.ready()` ([연결 라이프사이클](#연결-라이프사이클) 참조).
- 19금 방송 조회는 `SOOPChatException("API error: unknown error")` 대신 `AdultBroadcastException`(`AuthenticationException`의 하위 타입)으로 실패합니다. REASON 없이 실패한 다른 조회는 `"API error: RESULT=<코드>"`로 결과 코드를 알려 줍니다.
- `LiveDetail.toString()`은 `ChannelInfo`처럼 FTK를 `<redacted>`로 가립니다.

```java
chat.on(ChatEvent.NONE_TYPE, (UnknownEvent e) -> {
    System.out.println("알 수 없는 코드 " + e.code() + ": " + e.raw());
});
```

## 코드표 (Code Tables)

SOOP 소켓 프로토콜이 숫자로 전달하는 값(사용자 등급, 아이스 모드, 퇴장 사유)을 의미 있는 타입으로 디코딩합니다. 원시 필드는 그대로 유지되며, 아래 접근자는 **호출 시점에 지연 파싱**됩니다(디코딩 경로에는 영향 없음). 타입은 `com.github.getcurrentthread.soopapi.code` 패키지에 있습니다.

| 코드표 | 타입 | 지연 접근자 |
|--------|------|-------------|
| 사용자 등급 | `UserLevel` (`UserFlag` 주 + `UserFlag2` 보조) | `ChatMessageEvent.senderLevel()`, `ManagerChatEvent.senderLevel()`, `ChatUserEntry.level()`, `AdminChatUserEntry.level()`, `LoginEvent.userLevel()`, `JoinChannelEvent.userLevel()`, `SetUserFlagEvent.oldLevel()`/`newLevel()`, `SetAdminFlagEvent.level()`, `SetNicknameEvent.level()`, `SetSubBjEvent.level()` |
| 아이스(채팅 제한) 모드 | `ChatIceType` (+ `ChatIceType.Flag`) | `IceModeEvent`·`IceModeExEvent`·`GetIceModeRelayEvent` 의 `iceType()`, `iceFlags()`, `isIceFlagMode()`. 그룹 비트마스크가 `freezeType`에 오는 `IceModeExEvent`·`GetIceModeRelayEvent`는 `freezeFlags()` |
| 채널 퇴장 사유 | `ChatQuitStatus` | `QuitChannelEvent.quitStatus()` |

```java
client.on(ChatEvent.CHAT_MESSAGE, (String bid, ChatMessageEvent e) -> {
    UserLevel level = e.senderLevel();          // "81952|32768" → 파싱
    if (level.has(UserFlag.BJ)) { /* 방송인 */ }
    if (level.has(UserFlag2.TOPCLAN)) { /* 보조 그룹 플래그 */ }
});

client.on(ChatEvent.QUIT_CHANNEL, (String bid, QuitChannelEvent e) -> {
    if (e.quitStatus() == ChatQuitStatus.ADMKICK) { /* 운영자 강제 퇴장 */ }
});

client.on(ChatEvent.ICE_MODE, (String bid, IceModeEvent e) -> {
    if (e.isIceFlagMode()) {
        Set<ChatIceType.Flag> flags = e.iceFlags();   // v2 비트 플래그
    } else {
        ChatIceType type = e.iceType();               // 레거시 0~4
    }
});
```

> 사용자 등급 플래그는 `"주|보조"` 형식의 문자열이며 두 정수는 서로 다른 비트 의미를 가집니다(예: `16`이 주 그룹에서는 `GUEST`, 보조 그룹에서는 `GAMEGOD`). 그래서 주 그룹은 `UserFlag`, 보조 그룹은 `UserFlag2`로 각각 분해합니다. 각 그룹은 부호 있는 표기와 부호 없는 32비트 표기(`"2147483648"`)를 모두 받고, 반환되는 집합은 수정할 수 없습니다. 알 수 없는 코드는 센티넬(`UNKNOWN` / `UserLevel.EMPTY`)을 반환하며 예외를 던지지 않습니다.

## AI 지원 문서

이 프로젝트는 LLM/AI 도구가 코드베이스를 빠르게 이해할 수 있도록 AI-ready 문서를 제공합니다.

- [`llms.txt`](llms.txt) — 라이브러리 사용자를 위한 간결한 API 가이드
- [`llms-full.txt`](llms-full.txt) — 기여자를 위한 아키텍처·내부 구조 전체 레퍼런스

AI 코딩 도구(Cursor, Claude Code, GitHub Copilot 등)와 함께 작업할 때, 프롬프트에 아래 내용을 포함하면 더 정확한 코드를 생성할 수 있습니다:

> 이 프로젝트는 SOOP 채팅 API Java 라이브러리입니다. `llms.txt` 또는 `llms-full.txt` 파일을 참고하여 프로젝트의 구조, API, 이벤트 시스템을 이해한 후 작업해 주세요.

## 기여하기

기여는 언제나 환영합니다! Pull Request를 제출해 주세요.

이 프로젝트가 도움이 되셨다면, ⭐ 별을 눌러주세요. 감사합니다!

## 라이선스

이 프로젝트는 MIT License 하에 라이선스가 부여됩니다. 자세한 내용은 [LICENSE](LICENSE) 파일을 참조하세요.

## 면책 조항

이는 비공식 API이며 SOOP와 제휴되거나 승인되지 않았습니다. 사용에 따른 책임은 사용자에게 있습니다.

_주의: SOOP 플랫폼의 웹소켓 통신 방식이 변경되면 동작하지 않을 수 있습니다._
