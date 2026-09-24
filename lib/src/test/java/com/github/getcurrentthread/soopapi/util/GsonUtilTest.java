package com.github.getcurrentthread.soopapi.util;

import static org.junit.jupiter.api.Assertions.*;

import java.math.BigInteger;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.google.gson.JsonParseException;

class GsonUtilTest {

    @Test
    void integerValue() {
        Map<String, Object> result = GsonUtil.fromJson("{\"count\": 42}");
        assertInstanceOf(Integer.class, result.get("count"));
        assertEquals(42, result.get("count"));
    }

    @Test
    void zeroValue() {
        Map<String, Object> result = GsonUtil.fromJson("{\"val\": 0}");
        assertInstanceOf(Integer.class, result.get("val"));
        assertEquals(0, result.get("val"));
    }

    @Test
    void negativeInteger() {
        Map<String, Object> result = GsonUtil.fromJson("{\"val\": -5}");
        assertInstanceOf(Integer.class, result.get("val"));
        assertEquals(-5, result.get("val"));
    }

    @Test
    void largeLongValue_p2Fix() {
        // 2^53 + 1 = 9007199254740993
        // P2-1 수정이 해결하는 정확한 케이스: 이전에는 doubleValue() == intValue()
        // 비교 시 double이 이 값을 정확히 표현할 수 없어 정밀도가 손실되었다.
        // 문자열 기반 파싱 방식은 전체 long 값을 보존한다.
        Map<String, Object> result = GsonUtil.fromJson("{\"id\": 9007199254740993}");
        assertInstanceOf(Long.class, result.get("id"));
        assertEquals(9007199254740993L, result.get("id"));
    }

    @Test
    void floatValue() {
        Map<String, Object> result = GsonUtil.fromJson("{\"price\": 19.99}");
        assertInstanceOf(Double.class, result.get("price"));
        assertEquals(19.99, (Double) result.get("price"), 0.001);
    }

    @Test
    void scientificNotation() {
        Map<String, Object> result = GsonUtil.fromJson("{\"val\": 1e5}");
        assertInstanceOf(Double.class, result.get("val"));
        assertEquals(100000.0, result.get("val"));
    }

    @Test
    void booleanValue() {
        Map<String, Object> result = GsonUtil.fromJson("{\"active\": true}");
        assertInstanceOf(Boolean.class, result.get("active"));
        assertEquals(true, result.get("active"));
    }

    @Test
    void stringValue() {
        Map<String, Object> result = GsonUtil.fromJson("{\"name\": \"test\"}");
        assertInstanceOf(String.class, result.get("name"));
        assertEquals("test", result.get("name"));
    }

    @Test
    void nullInput() {
        Map<String, Object> result = GsonUtil.fromJson(null);
        assertNull(result);
    }

    @Test
    @SuppressWarnings("unchecked")
    void nestedObject() {
        Map<String, Object> result = GsonUtil.fromJson("{\"outer\": {\"inner\": 1}}");
        assertInstanceOf(Map.class, result.get("outer"));
        Map<String, Object> nested = (Map<String, Object>) result.get("outer");
        assertInstanceOf(Integer.class, nested.get("inner"));
        assertEquals(1, nested.get("inner"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void arrayValues() {
        Map<String, Object> result = GsonUtil.fromJson("{\"items\": [1, 2, 3]}");
        assertInstanceOf(List.class, result.get("items"));
        List<Object> items = (List<Object>) result.get("items");
        assertEquals(3, items.size());
        for (Object item : items) {
            assertInstanceOf(Integer.class, item);
        }
        assertEquals(1, items.get(0));
        assertEquals(2, items.get(1));
        assertEquals(3, items.get(2));
    }

    @Test
    void maxIntBoundary() {
        Map<String, Object> result = GsonUtil.fromJson("{\"val\": 2147483647}");
        assertInstanceOf(Integer.class, result.get("val"));
        assertEquals(Integer.MAX_VALUE, result.get("val"));
    }

    @Test
    void beyondMaxInt() {
        Map<String, Object> result = GsonUtil.fromJson("{\"val\": 2147483648}");
        assertInstanceOf(Long.class, result.get("val"));
        assertEquals(2147483648L, result.get("val"));
    }

    @Test
    void maxLongBoundary() {
        Map<String, Object> result = GsonUtil.fromJson("{\"val\": 9223372036854775807}");
        assertInstanceOf(Long.class, result.get("val"));
        assertEquals(Long.MAX_VALUE, result.get("val"));
    }

    @Test
    void beyondLongRange_keepsExactBigInteger() {
        Map<String, Object> result =
                GsonUtil.fromJson(
                        "{\"big\": 12345678901234567891, \"neg\": -12345678901234567891}");
        assertEquals(new BigInteger("12345678901234567891"), result.get("big"));
        assertEquals(new BigInteger("-12345678901234567891"), result.get("neg"));
    }

    @Test
    void jsonNullLiteral_returnsNull() {
        assertNull(GsonUtil.fromJson("null"));
    }

    @Test
    void arrayRoot_throwsJsonParseException() {
        assertThrows(JsonParseException.class, () -> GsonUtil.fromJson("[1,2]"));
    }

    @Test
    void primitiveRoot_throwsJsonParseException() {
        assertThrows(JsonParseException.class, () -> GsonUtil.fromJson("\"text\""));
        assertThrows(JsonParseException.class, () -> GsonUtil.fromJson("42"));
    }
}
