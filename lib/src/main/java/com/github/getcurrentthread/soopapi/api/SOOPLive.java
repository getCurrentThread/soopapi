package com.github.getcurrentthread.soopapi.api;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.github.getcurrentthread.soopapi.api.model.AuthCookie;
import com.github.getcurrentthread.soopapi.api.model.LiveDetail;
import com.github.getcurrentthread.soopapi.exception.SOOPChatException;
import com.github.getcurrentthread.soopapi.model.ChannelInfo;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

public class SOOPLive {
    private static final Logger LOGGER = Logger.getLogger(SOOPLive.class.getName());
    private static final String PLAYER_LIVE_URL =
            "https://live.sooplive.com/afreeca/player_live_api.php";
    private static final String PLAY_URL = "https://play.sooplive.com/";
    private static final Pattern BNO_PATTERN =
            Pattern.compile(
                    "<meta property=\"og:image\" content=\"https://liveimg\\.sooplive\\.com/m/(\\d+)");
    private static final Pattern BNO_ALT_PATTERN = Pattern.compile("\"bno\"\\s*:\\s*\"?(\\d+)\"?");

    private final SOOPHttpClient httpClient;

    public SOOPLive(SOOPHttpClient httpClient) {
        this.httpClient = httpClient;
    }

    public CompletableFuture<String> getBno(String streamerId) {
        return httpClient
                .get(PLAY_URL + URLEncoder.encode(streamerId, StandardCharsets.UTF_8))
                .thenApply(
                        response -> {
                            if (response.statusCode() != 200) {
                                throw new SOOPChatException(
                                        "HTTP request failed. Status code: "
                                                + response.statusCode());
                            }

                            String body = response.body();

                            Matcher matcher = BNO_PATTERN.matcher(body);

                            if (matcher.find()) {
                                return matcher.group(1);
                            }

                            Matcher altMatcher = BNO_ALT_PATTERN.matcher(body);

                            if (altMatcher.find()) {
                                return altMatcher.group(1);
                            }

                            throw new SOOPChatException(
                                    "Failed to retrieve BNO. The stream may be offline or an error occurred.");
                        });
    }

    public CompletableFuture<LiveDetail> detail(String streamerId) {
        return getBno(streamerId).thenCompose(bno -> detail(streamerId, bno));
    }

    public CompletableFuture<LiveDetail> detail(String streamerId, String bno) {
        return detail(streamerId, bno, null);
    }

    public CompletableFuture<LiveDetail> detail(
            String streamerId, String bno, AuthCookie authCookie) {
        String encodedStreamerId = URLEncoder.encode(streamerId, StandardCharsets.UTF_8);
        String requestBody =
                String.format(
                        "bid=%s&bno=%s&type=live&confirm_adult=false&player_type=html5&mode=landing&from_api=0&pwd=&stream_type=common&quality=HD",
                        encodedStreamerId, bno);

        String cookieHeader = buildCookieHeader(authCookie);

        return httpClient
                .postForm(PLAYER_LIVE_URL + "?bjid=" + encodedStreamerId, requestBody, cookieHeader)
                .thenApply(
                        response -> {
                            if (response.statusCode() != 200) {
                                throw new SOOPChatException(
                                        "Failed to retrieve live stream info. Status code: "
                                                + response.statusCode());
                            }
                            return parseLiveDetail(response.body(), bno);
                        });
    }

    private LiveDetail parseLiveDetail(String body, String bno) {
        try {
            JsonObject json = JsonParser.parseString(body).getAsJsonObject();

            if (!json.has("CHANNEL")) {
                int result = JsonFields.getInt(json, "RESULT", 0);
                if (result != 1) {
                    String reason = JsonFields.getString(json, "REASON", "unknown error");
                    throw new SOOPChatException("API error: " + reason);
                }
                throw new SOOPChatException("Response does not contain CHANNEL information");
            }

            JsonObject channel = json.getAsJsonObject("CHANNEL");

            int result = JsonFields.getInt(channel, "RESULT", JsonFields.getInt(json, "RESULT", 0));

            if (result != 1) {
                String reason =
                        JsonFields.getString(
                                channel,
                                "REASON",
                                JsonFields.getString(json, "REASON", "unknown error"));
                throw new SOOPChatException("API error: " + reason);
            }

            validateField(channel, "BJID");
            validateField(channel, "TITLE");
            validateField(channel, "CHDOMAIN");
            validateField(channel, "CHATNO");
            validateField(channel, "FTK");
            validateField(channel, "CHPT");

            return new LiveDetail(
                    channel.get("BJID").getAsString(),
                    bno,
                    channel.get("TITLE").getAsString(),
                    channel.get("CHDOMAIN").getAsString().toLowerCase(Locale.ROOT),
                    channel.get("CHATNO").getAsString(),
                    channel.get("FTK").getAsString(),
                    String.valueOf(channel.get("CHPT").getAsInt() + 1),
                    result,
                    JsonFields.getString(channel, "BPS", ""),
                    JsonFields.getString(channel, "geo_cc", ""),
                    JsonFields.getString(channel, "geo_rc", ""),
                    JsonFields.getString(channel, "acpt_lang", ""),
                    JsonFields.getString(channel, "svc_lang", ""));
        } catch (SOOPChatException e) {
            throw e;
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "Error parsing live stream info", e);
            throw new SOOPChatException("Failed to parse live stream info", e);
        }
    }

    public ChannelInfo toChannelInfo(LiveDetail detail) {
        return new ChannelInfo(
                detail.chatDomain(),
                detail.chatNo(),
                detail.ftk(),
                detail.title(),
                detail.bjId(),
                detail.chatPort(),
                detail.bps(),
                detail.geoCC(),
                detail.geoRC(),
                detail.acptLang(),
                detail.svcLang());
    }

    private static void validateField(JsonObject json, String fieldName) {
        if (!json.has(fieldName) || json.get(fieldName).isJsonNull()) {
            throw new SOOPChatException("Required field missing: " + fieldName);
        }
    }

    private String buildCookieHeader(AuthCookie authCookie) {
        if (authCookie == null || !authCookie.isAuthenticated()) {
            return null;
        }
        return String.join(
                "; ",
                "AuthTicket=" + authCookie.authTicket(),
                "_au=" + authCookie.au(),
                "UserTicket=" + authCookie.userTicket(),
                "RDB=" + authCookie.rdb());
    }
}
