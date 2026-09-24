package com.github.getcurrentthread.soopapi.util;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

import com.github.getcurrentthread.soopapi.constant.SOOPConstants;

class SOOPChatUtilsTest {

    // --- safeParseInt 테스트 ---

    @Test
    void safeParseInt_validInt() {
        assertEquals(42, SOOPChatUtils.safeParseInt("42", 0));
    }

    @Test
    void safeParseInt_negativeInt() {
        assertEquals(-1, SOOPChatUtils.safeParseInt("-1", 0));
    }

    @Test
    void safeParseInt_nonNumeric_returnsDefault() {
        assertEquals(99, SOOPChatUtils.safeParseInt("abc", 99));
    }

    @Test
    void safeParseInt_emptyString_returnsDefault() {
        assertEquals(99, SOOPChatUtils.safeParseInt("", 99));
    }

    @Test
    void safeParseInt_null_returnsDefault() {
        assertEquals(99, SOOPChatUtils.safeParseInt(null, 99));
    }

    @Test
    void safeParseInt_overflow_returnsDefault() {
        assertEquals(99, SOOPChatUtils.safeParseInt("99999999999", 99));
    }

    // --- safeParseLong 테스트 ---

    @Test
    void safeParseLong_validLong() {
        assertEquals(42L, SOOPChatUtils.safeParseLong("42", 0L));
    }

    @Test
    void safeParseLong_largeLong() {
        assertEquals(9007199254740993L, SOOPChatUtils.safeParseLong("9007199254740993", 0L));
    }

    @Test
    void safeParseLong_nonNumeric_returnsDefault() {
        assertEquals(99L, SOOPChatUtils.safeParseLong("abc", 99L));
    }

    @Test
    void safeParseLong_null_returnsDefault() {
        assertEquals(99L, SOOPChatUtils.safeParseLong(null, 99L));
    }

    // --- utf8ByteLength & calculateByteSize 테스트 ---

    @Test
    void utf8ByteLength_ascii() {
        assertEquals(5, SOOPChatUtils.utf8ByteLength("hello"));
    }

    @Test
    void calculateByteSize_ascii() {
        assertEquals(11, SOOPChatUtils.calculateByteSize("hello"));
    }

    @Test
    void utf8ByteLength_korean() {
        assertEquals(6, SOOPChatUtils.utf8ByteLength("\ud55c\uae00"));
    }

    @Test
    void calculateByteSize_korean() {
        assertEquals(12, SOOPChatUtils.calculateByteSize("\ud55c\uae00"));
    }

    // --- splitFields \ud14c\uc2a4\ud2b8 ---

    @Test
    void splitFields_matchesStringSplitWithNegativeLimit() {
        String f = SOOPConstants.F;
        String[] inputs = {
            "",
            "a",
            f,
            f + f,
            "a" + f + "b",
            "a" + f + f + "b",
            f + "a" + f,
            "a" + f + "b" + f + f,
            "\ud55c" + f + "\uae00" + f + "x",
        };
        String prefix = "HEAD" + f;
        for (String body : inputs) {
            String packet = prefix + body;
            assertArrayEquals(
                    body.split(f, -1),
                    SOOPChatUtils.splitFields(packet, prefix.length()),
                    () -> "Mismatch for body: " + body.replace(f, "<F>"));
        }
    }

    // --- parseServiceCode \ud14c\uc2a4\ud2b8 ---

    @Test
    void parseServiceCode_readsFourDigitsAfterLastTab() {
        String header = SOOPConstants.ESC + "0005" + "000010" + "00";
        assertEquals(5, SOOPChatUtils.parseServiceCode(header));
        String packet = header + SOOPConstants.F + "body\twith\ttabs";
        assertEquals(5, SOOPChatUtils.parseServiceCode(packet, 0, header.length()));
    }

    @Test
    void parseServiceCode_rejectsMalformedHeaders() {
        assertEquals(-1, SOOPChatUtils.parseServiceCode("0005000010"), "no TAB");
        assertEquals(-1, SOOPChatUtils.parseServiceCode(SOOPConstants.ESC + "005"), "too short");
        assertEquals(-1, SOOPChatUtils.parseServiceCode(SOOPConstants.ESC + "-003000010"));
        assertEquals(-1, SOOPChatUtils.parseServiceCode(SOOPConstants.ESC + "+005000010"));
        assertEquals(-1, SOOPChatUtils.parseServiceCode(SOOPConstants.ESC + "00a5000010"));
        assertEquals(-1, SOOPChatUtils.parseServiceCode(null));
    }
}
