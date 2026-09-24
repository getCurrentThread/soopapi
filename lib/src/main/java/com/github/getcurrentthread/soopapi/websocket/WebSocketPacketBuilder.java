package com.github.getcurrentthread.soopapi.websocket;

import com.github.getcurrentthread.soopapi.constant.SOOPConstants;
import com.github.getcurrentthread.soopapi.model.ChannelInfo;
import com.github.getcurrentthread.soopapi.util.SOOPChatUtils;

public class WebSocketPacketBuilder {
    /** 연결 유지(keep-alive) ping 패킷의 명령 코드. */
    static final String CMD_PING = "0000";

    /** 초기 연결 핸드셰이크의 명령 코드. */
    static final String CMD_CONNECT = "0001";

    /** 채팅 채널 입장의 명령 코드. */
    static final String CMD_JOIN = "0002";

    /** 채팅 메시지 전송의 명령 코드. */
    static final String CMD_CHAT = "0005";

    /** 귓말(다이렉트 채팅) 전송의 명령 코드. */
    static final String CMD_DIRECT_CHAT = "0009";

    /** 인증된 사용자 입장 정보의 명령 코드. */
    static final String CMD_ENTER_INFO = "0012";

    /** 모든 패킷의 길이 필드 뒤에 추가되는 고정 접미사. */
    private static final String PACKET_SUFFIX = "00";

    private static final char ESC_CHAR = SOOPConstants.ESC.charAt(0);

    /** 길이 필드 너비 — 바이트 길이가 이 문자 수만큼 0으로 패딩됩니다. */
    private static final int LENGTH_FIELD_WIDTH = 6;

    /** 길이 필드로 표현할 수 있는 최대 payload 바이트 수. */
    static final int MAX_PAYLOAD_BYTES = 999_999;

    /** 연결 핸드셰이크 시 전송되는 채팅 프로토콜 버전. */
    private static final String PROTOCOL_VERSION = "16";

    private static final String PING_PACKET = buildPacket(CMD_PING, SOOPConstants.F);

    public static String createPingPacket() {
        return PING_PACKET;
    }

    public static String createConnectPacket() {
        return createConnectPacket(null);
    }

    public static String createConnectPacket(String authTicket) {
        String payload;
        if (authTicket != null && !authTicket.isEmpty()) {
            payload =
                    SOOPConstants.F
                            + authTicket
                            + SOOPConstants.F.repeat(2)
                            + PROTOCOL_VERSION
                            + SOOPConstants.F;
        } else {
            payload = SOOPConstants.F.repeat(3) + PROTOCOL_VERSION + SOOPConstants.F;
        }
        return buildPacket(CMD_CONNECT, payload);
    }

    public static String createJoinPacket(ChannelInfo channelInfo) {
        return createJoinPacket(channelInfo, null, null);
    }

    public static String createJoinPacket(ChannelInfo channelInfo, String authTicket, String uuid) {
        StringBuilder payload = new StringBuilder();
        payload.append(SOOPConstants.F).append(channelInfo.CHATNO());

        if (authTicket != null && !authTicket.isEmpty()) {
            payload.append(SOOPConstants.F).append(channelInfo.FTK());
            payload.append(SOOPConstants.F).append("0");
            payload.append(SOOPConstants.F);

            String logQuery = buildLogQuery(channelInfo, uuid);
            payload.append("log")
                    .append(SOOPConstants.ELEMENT_START)
                    .append(logQuery)
                    .append(SOOPConstants.ELEMENT_END);

            payload.append("pwd")
                    .append(SOOPConstants.ELEMENT_START)
                    .append(SOOPConstants.ELEMENT_END);

            payload.append("auth_info")
                    .append(SOOPConstants.ELEMENT_START)
                    .append(authTicket)
                    .append(SOOPConstants.ELEMENT_END);

            payload.append("pver")
                    .append(SOOPConstants.ELEMENT_START)
                    .append("1")
                    .append(SOOPConstants.ELEMENT_END);

            payload.append("access_system")
                    .append(SOOPConstants.ELEMENT_START)
                    .append("html5")
                    .append(SOOPConstants.ELEMENT_END);

            payload.append(SOOPConstants.F);
        } else {
            payload.append(SOOPConstants.F.repeat(5));
        }

        return buildPacket(CMD_JOIN, payload.toString());
    }

    public static String createEnterInfoPacket(String synAck) {
        String payload = SOOPConstants.F + synAck + SOOPConstants.F + "0" + SOOPConstants.F;
        return buildPacket(CMD_ENTER_INFO, payload);
    }

    /**
     * 채팅 전송 패킷을 생성합니다.
     *
     * @throws IllegalArgumentException {@code message}가 null이거나, 필드 구분자·ESC·짝 없는 surrogate를 포함하거나,
     *     패킷이 너무 긴 경우
     */
    public static String createChatPacket(String message) {
        requireUserField("message", message);
        return buildPacket(CMD_CHAT, SOOPConstants.F + message + SOOPConstants.F.repeat(6));
    }

    /**
     * 귓말(다이렉트 채팅) 전송 패킷을 생성합니다.
     *
     * <p>페이로드는 {@code F + message + F + targetId + F} 형태이며, {@code targetId}는 받는 사람의 SOOP 로그인 ID(예:
     * {@code "targetUser"})입니다. 닉네임이나 런타임 {@code (n)} 접미사 형태가 아닙니다.
     *
     * @param targetId 받는 사람의 로그인 ID
     * @param message 전송할 메시지
     * @return 송신 가능한 귓말 패킷 문자열
     * @throws IllegalArgumentException 인자가 null이거나, 필드 구분자·ESC·짝 없는 surrogate를 포함하거나, 패킷이 너무 긴 경우
     */
    public static String createWhisperPacket(String targetId, String message) {
        requireUserField("targetId", targetId);
        requireUserField("message", message);
        return buildPacket(
                CMD_DIRECT_CHAT,
                SOOPConstants.F + message + SOOPConstants.F + targetId + SOOPConstants.F);
    }

    /**
     * 사용자 입력이 패킷 구조를 깨지 않는지 확인합니다. 필드 구분자(F)가 들어가면 서버가 나머지를 다른 필드로 읽고, ESC는 패킷 헤더의 시작입니다. 짝 없는
     * surrogate는 UTF-8로 인코딩할 수 없어 길이 필드와 실제 바이트 수가 어긋납니다.
     */
    private static void requireUserField(String name, String value) {
        if (value == null) {
            throw new IllegalArgumentException(name + " must not be null");
        }
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == SOOPConstants.F_CHAR || c == ESC_CHAR) {
                throw new IllegalArgumentException(
                        name + " must not contain control character U+" + hex(c));
            }
            if (Character.isHighSurrogate(c)
                    && i + 1 < value.length()
                    && Character.isLowSurrogate(value.charAt(i + 1))) {
                i++;
            } else if (Character.isSurrogate(c)) {
                throw new IllegalArgumentException(
                        name + " contains an unpaired surrogate U+" + hex(c));
            }
        }
    }

    private static String hex(char c) {
        return String.format("%04X", (int) c);
    }

    private static String buildPacket(String command, String data) {
        int byteLength = SOOPChatUtils.utf8ByteLength(data);
        if (byteLength > MAX_PAYLOAD_BYTES) {
            throw new IllegalArgumentException(
                    "Packet payload too large: "
                            + byteLength
                            + " > "
                            + MAX_PAYLOAD_BYTES
                            + " bytes");
        }
        String lengthStr = String.valueOf(byteLength);
        StringBuilder sb =
                new StringBuilder(
                        SOOPConstants.ESC.length()
                                + command.length()
                                + LENGTH_FIELD_WIDTH
                                + PACKET_SUFFIX.length()
                                + data.length());
        sb.append(SOOPConstants.ESC);
        sb.append(command);
        for (int i = lengthStr.length(); i < LENGTH_FIELD_WIDTH; i++) {
            sb.append('0');
        }
        sb.append(lengthStr);
        sb.append(PACKET_SUFFIX);
        sb.append(data);
        return sb.toString();
    }

    private static String buildLogQuery(ChannelInfo channelInfo, String uuid) {
        StringBuilder sb = new StringBuilder();
        appendParam(sb, "set_bps", channelInfo.BPS());
        appendParam(sb, "view_bps", channelInfo.BPS());
        appendParam(sb, "quality", "normal");
        appendParam(sb, "uuid", uuid != null ? uuid : "");
        appendParam(sb, "geo_cc", channelInfo.geoCC());
        appendParam(sb, "geo_rc", channelInfo.geoRC());
        appendParam(sb, "acpt_lang", channelInfo.acptLang());
        appendParam(sb, "svc_lang", channelInfo.svcLang());
        appendParam(sb, "subscribe", "0");
        appendParam(sb, "lowlatency", "0");
        appendParam(sb, "mode", "landing");
        return sb.toString();
    }

    private static void appendParam(StringBuilder sb, String key, String value) {
        sb.append(SOOPConstants.SPACE)
                .append("&")
                .append(SOOPConstants.SPACE)
                .append(key)
                .append(SOOPConstants.SPACE)
                .append("=")
                .append(SOOPConstants.SPACE)
                .append(value != null ? value : "");
    }
}
