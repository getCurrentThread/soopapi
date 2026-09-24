package com.github.getcurrentthread.soopapi.api;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/** API 응답 JSON에서 필드를 null-safe하게 읽는 공용 헬퍼. 키가 없거나 JSON null이면 기본값을 반환한다. */
final class JsonFields {

    private JsonFields() {}

    /**
     * 응답 본문을 JSON 객체로 읽는다. Gson의 {@code getAsJsonObject()}는 객체가 아닌 본문을 예외 메시지에 그대로 싣는데, 로그인 응답이나
     * FTK가 든 방송 정보 응답이 그 메시지를 거쳐 로그에 남지 않도록 본문 없이 실패한다.
     *
     * @throws IllegalStateException 본문이 JSON 객체가 아닐 때
     */
    static JsonObject parseObject(String body) {
        JsonElement root = JsonParser.parseString(body);
        if (!root.isJsonObject()) {
            throw new IllegalStateException("Response is not a JSON object");
        }
        return root.getAsJsonObject();
    }

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
