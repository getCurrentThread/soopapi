package com.github.getcurrentthread.soopapi.websocket;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

import com.github.getcurrentthread.soopapi.constant.SOOPConstants;
import com.github.getcurrentthread.soopapi.model.ChannelInfo;

class WebSocketPacketBuilderTest {

    @Test
    void pingPacket_hasCorrectStructure() {
        String packet = WebSocketPacketBuilder.createPingPacket();

        assertTrue(packet.startsWith(SOOPConstants.ESC), "Packet should start with ESC");
        // ESC(2) + CMD(4) + LENGTH(6) + SUFFIX(2) + 페이로드
        String command =
                packet.substring(SOOPConstants.ESC.length(), SOOPConstants.ESC.length() + 4);
        assertEquals(WebSocketPacketBuilder.CMD_PING, command);
    }

    @Test
    void pingPacket_isCached() {
        String first = WebSocketPacketBuilder.createPingPacket();
        String second = WebSocketPacketBuilder.createPingPacket();
        assertSame(first, second, "Ping packet should be cached (same instance)");
    }

    @Test
    void connectPacket_anonymous_hasCorrectCommand() {
        String packet = WebSocketPacketBuilder.createConnectPacket();

        assertTrue(packet.startsWith(SOOPConstants.ESC));
        String command =
                packet.substring(SOOPConstants.ESC.length(), SOOPConstants.ESC.length() + 4);
        assertEquals(WebSocketPacketBuilder.CMD_CONNECT, command);
    }

    @Test
    void connectPacket_anonymous_containsProtocolVersion() {
        String packet = WebSocketPacketBuilder.createConnectPacket();
        // 익명 연결 페이로드: F F F "16" F
        assertTrue(
                packet.contains("16"), "Anonymous connect packet should contain protocol version");
    }

    @Test
    void connectPacket_authenticated_containsAuthTicket() {
        String authTicket = "test_auth_ticket_123";
        String packet = WebSocketPacketBuilder.createConnectPacket(authTicket);

        assertTrue(packet.contains(authTicket), "Authenticated packet should contain auth ticket");
        String command =
                packet.substring(SOOPConstants.ESC.length(), SOOPConstants.ESC.length() + 4);
        assertEquals(WebSocketPacketBuilder.CMD_CONNECT, command);
    }

    @Test
    void connectPacket_nullAuth_sameAsAnonymous() {
        String anonymous = WebSocketPacketBuilder.createConnectPacket(null);
        String explicit = WebSocketPacketBuilder.createConnectPacket();
        assertEquals(anonymous, explicit);
    }

    @Test
    void connectPacket_emptyAuth_sameAsAnonymous() {
        String emptyAuth = WebSocketPacketBuilder.createConnectPacket("");
        String explicit = WebSocketPacketBuilder.createConnectPacket();
        assertEquals(emptyAuth, explicit);
    }

    @Test
    void joinPacket_anonymous_hasCorrectCommand() {
        ChannelInfo channelInfo = createChannelInfo();
        String packet = WebSocketPacketBuilder.createJoinPacket(channelInfo);

        assertTrue(packet.startsWith(SOOPConstants.ESC));
        String command =
                packet.substring(SOOPConstants.ESC.length(), SOOPConstants.ESC.length() + 4);
        assertEquals(WebSocketPacketBuilder.CMD_JOIN, command);
    }

    @Test
    void joinPacket_anonymous_containsChatNo() {
        ChannelInfo channelInfo = createChannelInfo();
        String packet = WebSocketPacketBuilder.createJoinPacket(channelInfo);

        assertTrue(packet.contains("12345"), "Join packet should contain CHATNO");
    }

    @Test
    void joinPacket_authenticated_containsFTK() {
        ChannelInfo channelInfo = createChannelInfo();
        String packet =
                WebSocketPacketBuilder.createJoinPacket(channelInfo, "auth_ticket", "uuid_123");

        assertTrue(packet.contains("test_ftk"), "Authenticated join should contain FTK");
        assertTrue(packet.contains("uuid_123"), "Authenticated join should contain UUID in log");
    }

    @Test
    void joinPacket_authenticated_containsLogBlock() {
        ChannelInfo channelInfo = createChannelInfo();
        String packet =
                WebSocketPacketBuilder.createJoinPacket(channelInfo, "auth_ticket", "uuid_123");

        assertTrue(packet.contains("log"), "Authenticated join should contain log block");
        assertTrue(
                packet.contains(SOOPConstants.ELEMENT_START), "Log block should use ELEMENT_START");
        assertTrue(packet.contains(SOOPConstants.ELEMENT_END), "Log block should use ELEMENT_END");
    }

    @Test
    void chatPacket_hasCorrectCommand() {
        String packet = WebSocketPacketBuilder.createChatPacket("Hello World");

        assertTrue(packet.startsWith(SOOPConstants.ESC));
        String command =
                packet.substring(SOOPConstants.ESC.length(), SOOPConstants.ESC.length() + 4);
        assertEquals(WebSocketPacketBuilder.CMD_CHAT, command);
    }

    @Test
    void chatPacket_containsMessage() {
        String message = "Hello World";
        String packet = WebSocketPacketBuilder.createChatPacket(message);

        assertTrue(packet.contains(message), "Chat packet should contain the message");
    }

    @Test
    void whisperPacket_hasCorrectCommand() {
        String packet = WebSocketPacketBuilder.createWhisperPacket("targetUser", "hi");

        assertTrue(packet.startsWith(SOOPConstants.ESC));
        String command =
                packet.substring(SOOPConstants.ESC.length(), SOOPConstants.ESC.length() + 4);
        assertEquals(WebSocketPacketBuilder.CMD_DIRECT_CHAT, command);
    }

    @Test
    void whisperPacket_containsMessageAndTargetId() {
        String packet = WebSocketPacketBuilder.createWhisperPacket("targetUser", "Hello");

        assertTrue(packet.contains("Hello"), "Whisper packet should contain the message");
        assertTrue(packet.contains("targetUser"), "Whisper packet should contain the targetId");
    }

    @Test
    void whisperPacket_lengthField_matchesUtf8ByteLength() {
        // F + "안녕" + F + "targetUser" + F = 1 + 6 + 1 + 10 + 1 = 19 (한글은 UTF-8에서 글자당 3바이트)
        String packet = WebSocketPacketBuilder.createWhisperPacket("targetUser", "안녕");
        int lengthStart = SOOPConstants.ESC.length() + 4;
        String lengthField = packet.substring(lengthStart, lengthStart + 6);

        assertEquals("000019", lengthField);
    }

    @Test
    void whisperPacket_lengthField_isSixZeroPaddedDigits() {
        String packet = WebSocketPacketBuilder.createWhisperPacket("user", "hi");
        int lengthStart = SOOPConstants.ESC.length() + 4;
        String lengthField = packet.substring(lengthStart, lengthStart + 6);

        assertTrue(lengthField.matches("\\d{6}"), "Length field should be 6 zero-padded digits");
    }

    @Test
    void whisperPacket_fieldOrder_isMessageThenTarget() {
        // 페이로드 레이아웃 검증: F + message + F + targetId + F (메시지가 targetId보다 앞, 끝에 단일 F)
        String packet = WebSocketPacketBuilder.createWhisperPacket("targetUser", "msg");
        String expectedData =
                SOOPConstants.F + "msg" + SOOPConstants.F + "targetUser" + SOOPConstants.F;

        assertTrue(
                packet.endsWith(expectedData),
                "message must precede targetId, with a single trailing F");
    }

    @Test
    void enterInfoPacket_hasCorrectCommand() {
        String packet = WebSocketPacketBuilder.createEnterInfoPacket("syn_ack_value");

        assertTrue(packet.startsWith(SOOPConstants.ESC));
        String command =
                packet.substring(SOOPConstants.ESC.length(), SOOPConstants.ESC.length() + 4);
        assertEquals(WebSocketPacketBuilder.CMD_ENTER_INFO, command);
    }

    @Test
    void enterInfoPacket_containsSynAck() {
        String synAck = "my_syn_ack";
        String packet = WebSocketPacketBuilder.createEnterInfoPacket(synAck);

        assertTrue(packet.contains(synAck), "Enter info packet should contain synAck");
    }

    @Test
    void packetLengthField_isCorrectlyFormatted() {
        // 길이 필드는 6자리이며, 0으로 패딩되어야 함
        String packet = WebSocketPacketBuilder.createPingPacket();
        // ESC(2) + CMD(4) = 길이 필드 앞 6자
        int lengthStart = SOOPConstants.ESC.length() + 4;
        String lengthField = packet.substring(lengthStart, lengthStart + 6);

        // 길이 필드는 모두 숫자여야 함
        assertTrue(lengthField.matches("\\d{6}"), "Length field should be 6 zero-padded digits");
    }

    @Test
    void chatPacket_rejectsFieldSeparatorAndEsc() {
        assertThrows(
                IllegalArgumentException.class,
                () -> WebSocketPacketBuilder.createChatPacket("a" + SOOPConstants.F + "b"));
        assertThrows(
                IllegalArgumentException.class,
                () -> WebSocketPacketBuilder.createChatPacket("a" + SOOPConstants.ESC + "0005"));
        assertThrows(
                IllegalArgumentException.class,
                () -> WebSocketPacketBuilder.createChatPacket(null));
    }

    @Test
    void whisperPacket_rejectsFieldSeparatorInEitherField() {
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        WebSocketPacketBuilder.createWhisperPacket(
                                "target" + SOOPConstants.F + "other", "hi"));
        assertThrows(
                IllegalArgumentException.class,
                () -> WebSocketPacketBuilder.createWhisperPacket("target", "hi" + SOOPConstants.F));
    }

    @Test
    void chatPacket_rejectsUnpairedSurrogate() {
        assertThrows(
                IllegalArgumentException.class,
                () -> WebSocketPacketBuilder.createChatPacket("broken \ud83d"));
        assertThrows(
                IllegalArgumentException.class,
                () -> WebSocketPacketBuilder.createChatPacket("\ude00 broken"));
    }

    @Test
    void chatPacket_acceptsSurrogatePairAndCountsFourBytes() {
        // F + 이모지(4바이트) + F×6 = 11
        String packet = WebSocketPacketBuilder.createChatPacket("\ud83d\ude00");
        int lengthStart = SOOPConstants.ESC.length() + 4;

        assertEquals("000011", packet.substring(lengthStart, lengthStart + 6));
    }

    @Test
    void chatPacket_rejectsPayloadThatOverflowsLengthField() {
        // 채팅 payload는 message + 7바이트(앞 F 1개, 뒤 F 6개)
        int maxMessage = WebSocketPacketBuilder.MAX_PAYLOAD_BYTES - 7;
        String packet = WebSocketPacketBuilder.createChatPacket("a".repeat(maxMessage));
        int lengthStart = SOOPConstants.ESC.length() + 4;
        assertEquals("999999", packet.substring(lengthStart, lengthStart + 6));

        assertThrows(
                IllegalArgumentException.class,
                () -> WebSocketPacketBuilder.createChatPacket("a".repeat(maxMessage + 1)));
    }

    @Test
    void chatPacket_withUnicode_handlesMultibyteCorrectly() {
        String message = "안녕하세요"; // 한글 문자 (UTF-8에서 각 3바이트)
        String packet = WebSocketPacketBuilder.createChatPacket(message);

        assertTrue(packet.contains(message));
        // 패킷이 올바르게 구성되었는지 확인 (ESC로 시작, 명령 포함)
        assertTrue(packet.startsWith(SOOPConstants.ESC));
    }

    private ChannelInfo createChannelInfo() {
        return new ChannelInfo(
                "chat.example.com",
                "12345",
                "test_ftk",
                "Test Stream",
                "test_bj",
                "8080",
                "1000",
                "KR",
                "11",
                "ko",
                "ko_KR");
    }
}
