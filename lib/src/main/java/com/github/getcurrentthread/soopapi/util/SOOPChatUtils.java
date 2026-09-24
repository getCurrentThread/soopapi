package com.github.getcurrentthread.soopapi.util;

import java.util.logging.Logger;

import com.github.getcurrentthread.soopapi.constant.SOOPConstants;

public class SOOPChatUtils {
    private static final Logger LOGGER = Logger.getLogger(SOOPChatUtils.class.getName());

    private SOOPChatUtils() {}

    /**
     * 바이트 크기를 계산합니다.
     *
     * @param string 크기를 계산할 문자열
     * @return 바이트 크기
     */
    public static int calculateByteSize(String string) {
        return utf8ByteLength(string) + 6;
    }

    /** 바이트 배열 할당 없이 UTF-8 바이트 길이를 계산합니다. */
    public static int utf8ByteLength(String s) {
        int count = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c <= 0x7F) {
                count++;
            } else if (c <= 0x7FF) {
                count += 2;
            } else if (Character.isHighSurrogate(c)) {
                count += 4;
                i++;
            } else {
                count += 3;
            }
        }
        return count;
    }

    /**
     * 문자열을 안전하게 int로 파싱합니다. 파싱 실패 시 기본값을 반환합니다.
     *
     * @param value 파싱할 문자열
     * @param defaultValue 파싱 실패 시 반환할 기본값
     * @return 파싱된 int 값 또는 기본값
     */
    public static int safeParseInt(String value, int defaultValue) {
        if (value == null || value.isEmpty()) {
            return defaultValue;
        }
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    /**
     * 문자열을 안전하게 long으로 파싱합니다. 파싱 실패 시 기본값을 반환합니다.
     *
     * @param value 파싱할 문자열
     * @param defaultValue 파싱 실패 시 반환할 기본값
     * @return 파싱된 long 값 또는 기본값
     */
    public static long safeParseLong(String value, long defaultValue) {
        if (value == null || value.isEmpty()) {
            return defaultValue;
        }
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    /**
     * CompletionException 체인을 풀어서 근본 원인을 반환합니다.
     *
     * @param throwable 풀어볼 예외
     * @return 근본 원인 예외
     */
    public static Throwable unwrapCompletionException(Throwable throwable) {
        Throwable cause = throwable;
        while (cause instanceof java.util.concurrent.CompletionException
                && cause.getCause() != null) {
            cause = cause.getCause();
        }
        return cause;
    }

    /**
     * 서비스 코드를 파싱합니다.
     *
     * @param header 헤더 문자열
     * @return 서비스 코드, 헤더가 올바르지 않으면 -1
     */
    public static int parseServiceCode(String header) {
        return header == null ? -1 : parseServiceCode(header, 0, header.length());
    }

    /**
     * {@code packet[start, end)} 구간의 헤더에서 서비스 코드를 할당 없이 파싱합니다. 마지막 TAB 뒤의 네 글자가 모두 ASCII 숫자여야 합니다.
     *
     * @return 0 이상의 서비스 코드, 헤더가 올바르지 않으면 -1
     */
    public static int parseServiceCode(String packet, int start, int end) {
        int tab = packet.lastIndexOf('\t', end - 1);
        if (tab < start) {
            return -1;
        }
        int from = tab + 1;
        if (end - from < 4) {
            LOGGER.fine(() -> "Service code field is too short: " + packet.substring(from, end));
            return -1;
        }
        int code = 0;
        for (int i = from; i < from + 4; i++) {
            int digit = packet.charAt(i) - '0';
            if (digit < 0 || digit > 9) {
                return -1;
            }
            code = code * 10 + digit;
        }
        return code;
    }

    /**
     * {@code s}의 {@code from} 위치부터 {@link SOOPConstants#F_CHAR}로 나눈 필드를 반환합니다. {@code
     * s.substring(from).split(F, -1)}과 결과가 같지만 중간 문자열과 리스트를 만들지 않습니다.
     */
    public static String[] splitFields(String s, int from) {
        int count = 1;
        for (int i = s.indexOf(SOOPConstants.F_CHAR, from);
                i >= 0;
                i = s.indexOf(SOOPConstants.F_CHAR, i + 1)) {
            count++;
        }
        String[] parts = new String[count];
        int begin = from;
        for (int n = 0; n < count - 1; n++) {
            int sep = s.indexOf(SOOPConstants.F_CHAR, begin);
            parts[n] = s.substring(begin, sep);
            begin = sep + 1;
        }
        parts[count - 1] = s.substring(begin);
        return parts;
    }
}
