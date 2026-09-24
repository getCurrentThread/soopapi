package com.github.getcurrentthread.soopapi.api;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Level;
import java.util.logging.Logger;

import com.github.getcurrentthread.soopapi.api.model.StationInfo;
import com.github.getcurrentthread.soopapi.exception.SOOPChatException;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

public class SOOPChannel {
    private static final Logger LOGGER = Logger.getLogger(SOOPChannel.class.getName());
    private static final String STATION_URL = "https://chapi.sooplive.co.kr/api/%s/station";

    private final SOOPHttpClient httpClient;

    public SOOPChannel(SOOPHttpClient httpClient) {
        this.httpClient = httpClient;
    }

    public CompletableFuture<StationInfo> station(String streamerId) {
        String url =
                String.format(STATION_URL, URLEncoder.encode(streamerId, StandardCharsets.UTF_8));

        return httpClient
                .get(url)
                .thenApply(
                        response -> {
                            if (response.statusCode() != 200) {
                                throw new SOOPChatException(
                                        "Failed to retrieve station info. Status code: "
                                                + response.statusCode());
                            }
                            return parseStation(response.body(), streamerId);
                        });
    }

    /**
     * 방송국 API 응답 본문을 {@link StationInfo}로 변환한다.
     *
     * <p>방송국 필드는 {@code station} 객체 아래에, 팬 수는 {@code station.upd.fan_cnt}에 있다. 루트의 {@code broad}가
     * JSON 객체이면 방송 중으로 본다.
     *
     * @throws SOOPChatException {@code station} 객체가 없거나 본문을 파싱할 수 없을 때
     */
    static StationInfo parseStation(String body, String streamerId) {
        try {
            JsonObject root = JsonParser.parseString(body).getAsJsonObject();

            JsonObject station = childObject(root, "station");
            if (station == null) {
                throw new SOOPChatException("Station info missing in response");
            }

            JsonObject upd = childObject(station, "upd");
            boolean isLive = childObject(root, "broad") != null;

            return new StationInfo(
                    JsonFields.getString(station, "user_id", streamerId),
                    JsonFields.getString(station, "user_nick", ""),
                    JsonFields.getLong(station, "station_no", 0),
                    JsonFields.getString(station, "station_name", ""),
                    JsonFields.getString(station, "station_title", ""),
                    isLive,
                    upd != null ? JsonFields.getInt(upd, "fan_cnt", 0) : 0);
        } catch (SOOPChatException e) {
            throw e;
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "Error parsing station info", e);
            throw new SOOPChatException("Failed to parse station info", e);
        }
    }

    private static JsonObject childObject(JsonObject json, String key) {
        return json.has(key) && json.get(key).isJsonObject() ? json.getAsJsonObject(key) : null;
    }
}
