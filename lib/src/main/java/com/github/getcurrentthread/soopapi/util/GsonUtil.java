package com.github.getcurrentthread.soopapi.util;

import java.lang.reflect.Type;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import com.google.gson.*;
import com.google.gson.reflect.TypeToken;

public class GsonUtil {
    private static final Type MAP_TYPE = new TypeToken<Map<String, Object>>() {}.getType();
    private static final Gson gson = createCustomGson();

    private static Gson createCustomGson() {
        return new GsonBuilder()
                .registerTypeAdapter(MAP_TYPE, new MapDeserializer())
                .setFieldNamingPolicy(FieldNamingPolicy.LOWER_CASE_WITH_UNDERSCORES)
                .create();
    }

    /**
     * JSON 객체 문자열을 {@code Map}으로 변환합니다.
     *
     * <p>입력이 {@code null}이거나 JSON {@code null}이면 {@code null}을 반환합니다. 형식이 잘못되었거나 루트가 객체가 아니면 {@link
     * JsonParseException}을 던집니다. 정수는 크기에 따라 {@code Integer}, {@code Long}, {@code BigInteger} 중
     * 하나로, 소수와 지수 표기는 {@code Double}로 변환합니다.
     */
    public static Map<String, Object> fromJson(String json) {
        return gson.fromJson(json, MAP_TYPE);
    }

    private static class MapDeserializer implements JsonDeserializer<Map<String, Object>> {
        @SuppressWarnings("unchecked")
        @Override
        public Map<String, Object> deserialize(
                JsonElement json, Type typeOfT, JsonDeserializationContext context)
                throws JsonParseException {
            // JSON null은 Gson이 먼저 걸러 null을 반환하므로 여기에는 오지 않는다.
            if (!json.isJsonObject()) {
                throw new JsonParseException(
                        "Expected a JSON object but was "
                                + (json.isJsonArray() ? "an array" : "a primitive"));
            }
            return (Map<String, Object>) ParseObjectFromElement.INSTANCE.apply(json);
        }
    }

    private enum ParseObjectFromElement implements Function<JsonElement, Object> {
        INSTANCE;

        @Override
        public Object apply(JsonElement input) {
            if (input == null || input.isJsonNull()) {
                return null;
            } else if (input.isJsonPrimitive()) {
                JsonPrimitive primitive = input.getAsJsonPrimitive();
                if (primitive.isNumber()) {
                    Number number = primitive.getAsNumber();
                    String numStr = number.toString();
                    if (!numStr.contains(".") && !numStr.contains("e") && !numStr.contains("E")) {
                        try {
                            long longVal = Long.parseLong(numStr);
                            if (longVal >= Integer.MIN_VALUE && longVal <= Integer.MAX_VALUE) {
                                return (int) longVal;
                            }
                            return longVal;
                        } catch (NumberFormatException e) {
                            // long 범위를 넘는 정수는 정밀도를 잃지 않도록 BigInteger로 보존한다.
                            try {
                                return new BigInteger(numStr);
                            } catch (NumberFormatException notInteger) {
                                return number.doubleValue();
                            }
                        }
                    }
                    return number.doubleValue();
                } else if (primitive.isBoolean()) {
                    return primitive.getAsBoolean();
                } else {
                    return primitive.getAsString();
                }
            } else if (input.isJsonArray()) {
                List<Object> list = new ArrayList<>();
                for (JsonElement element : input.getAsJsonArray()) {
                    list.add(apply(element));
                }
                return list;
            } else if (input.isJsonObject()) {
                Map<String, Object> map = new LinkedHashMap<>();
                for (Map.Entry<String, JsonElement> entry : input.getAsJsonObject().entrySet()) {
                    map.put(entry.getKey(), apply(entry.getValue()));
                }
                return map;
            }
            throw new JsonParseException(
                    "Unexpected JSON type: " + input.getClass().getSimpleName());
        }
    }
}
