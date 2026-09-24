package com.github.getcurrentthread.soopapi.api;

import com.google.gson.JsonObject;

/** API 응답 JSON에서 필드를 null-safe하게 읽는 공용 헬퍼. 키가 없거나 JSON null이면 기본값을 반환한다. */
final class JsonFields {

    private JsonFields() {}

    static String getString(JsonObject json, String key, String defaultValue) {
        return isPresent(json, key) ? json.get(key).getAsString() : defaultValue;
    }

    static int getInt(JsonObject json, String key, int defaultValue) {
        return isPresent(json, key) ? json.get(key).getAsInt() : defaultValue;
    }

    static long getLong(JsonObject json, String key, long defaultValue) {
        return isPresent(json, key) ? json.get(key).getAsLong() : defaultValue;
    }

    private static boolean isPresent(JsonObject json, String key) {
        return json.has(key) && !json.get(key).isJsonNull();
    }
}
