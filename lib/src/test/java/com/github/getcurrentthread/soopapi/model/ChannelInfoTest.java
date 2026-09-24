package com.github.getcurrentthread.soopapi.model;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class ChannelInfoTest {

    @Test
    void toStringRedactsFtk() {
        String s =
                new ChannelInfo("chat.example.invalid", "1", "FTKSECRET", "title1", "bj1", "8001")
                        .toString();

        assertFalse(s.contains("FTKSECRET"), s);
        assertTrue(s.contains("FTK: <redacted>"), s);
        assertTrue(s.contains("CHDOMAIN: chat.example.invalid"), s);
        assertTrue(s.contains("BJID: bj1"), s);
    }
}
