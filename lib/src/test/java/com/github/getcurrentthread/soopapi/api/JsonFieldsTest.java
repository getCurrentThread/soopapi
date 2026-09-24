package com.github.getcurrentthread.soopapi.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

class JsonFieldsTest {

    private static final JsonObject JSON =
            JsonParser.parseString("{\"s\":\"v\",\"i\":7,\"l\":9007199254740993,\"n\":null}")
                    .getAsJsonObject();

    @Test
    void presentValuesAreRead() {
        assertEquals("v", JsonFields.getString(JSON, "s", "d"));
        assertEquals(7, JsonFields.getInt(JSON, "i", -1));
        assertEquals(9007199254740993L, JsonFields.getLong(JSON, "l", -1L));
    }

    @Test
    void absentKeyReturnsDefault() {
        assertEquals("d", JsonFields.getString(JSON, "missing", "d"));
        assertEquals(-1, JsonFields.getInt(JSON, "missing", -1));
        assertEquals(-1L, JsonFields.getLong(JSON, "missing", -1L));
    }

    @Test
    void jsonNullReturnsDefault() {
        assertEquals("d", JsonFields.getString(JSON, "n", "d"));
        assertEquals(-1, JsonFields.getInt(JSON, "n", -1));
        assertEquals(-1L, JsonFields.getLong(JSON, "n", -1L));
    }

    @Test
    void parseObject_readsAnObject() {
        assertEquals("v", JsonFields.getString(JsonFields.parseObject("{\"s\":\"v\"}"), "s", "d"));
    }

    @Test
    void parseObject_failsWithoutEchoingANonObjectBody() {
        for (String body : new String[] {"[\"secret1\"]", "\"secret1\"", "secret1", ""}) {
            IllegalStateException e =
                    assertThrows(IllegalStateException.class, () -> JsonFields.parseObject(body));

            assertEquals("Response is not a JSON object", e.getMessage());
        }
    }
}
