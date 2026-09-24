package com.github.getcurrentthread.soopapi.api.model;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class LiveDetailTest {

    @Test
    void toStringRedactsTheFtk() {
        LiveDetail detail =
                new LiveDetail(
                        "bj1",
                        "1000",
                        "title1",
                        "chat.example.invalid",
                        "1",
                        "FTKSECRET",
                        "8001",
                        1);

        String s = detail.toString();

        assertFalse(s.contains("FTKSECRET"), s);
        assertTrue(s.contains("ftk=<redacted>"), s);
        assertTrue(s.contains("bjId=bj1"), s);
        assertTrue(s.contains("chatPort=8001"), s);
    }

    @Test
    void toStringHandlesAnEmptyFtk() {
        LiveDetail detail = new LiveDetail("bj1", "1000", "title1", "", "1", null, "8001", 1);

        assertTrue(detail.toString().contains("ftk=<empty>"));
    }
}
