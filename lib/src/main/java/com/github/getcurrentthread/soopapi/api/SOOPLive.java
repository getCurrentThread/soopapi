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
import com.github.getcurrentthread.soopapi.exception.AdultBroadcastException;
import com.github.getcurrentthread.soopapi.exception.SOOPChatException;
import com.github.getcurrentthread.soopapi.model.ChannelInfo;
import com.google.gson.JsonObject;

public class SOOPLive {
    private static final Logger LOGGER = Logger.getLogger(SOOPLive.class.getName());
    private static final String PLAYER_LIVE_URL =
            "https://live.sooplive.com/afreeca/player_live_api.php";
    private static final String PLAY_URL = "https://play.sooplive.com/";
    private static final Pattern BNO_PATTERN =
            Pattern.compile(
                    "<meta property=\"og:image\" content=\"https://liveimg\\.sooplive\\.com/m/(\\d+)");
    private static final Pattern BNO_ALT_PATTERN = Pattern.compile("\"bno\"\\s*:\\s*\"?(\\d+)\"?");
    // 19금 방송을 볼 수 없는 요청(익명 포함)에 오는 CHANNEL.RESULT. REASON과 채팅 접속 정보(BJID·CHDOMAIN·CHATNO·FTK·CHPT)가
    // 빠지고 TITLE·BPS 같은 방송 정보만 온다. 익명 요청은 confirm_adult=true여도 같다.
    private static final int RESULT_ADULT_ONLY = -6;

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
        boolean authenticated = authCookie != null && authCookie.isAuthenticated();
        // 연령 인증된 로그인의 쿠키면 confirm_adult와 상관없이 채팅 접속 정보가 온다. 19금 확인을 사용자 대신 하지 않도록 늘 false로 보낸다.
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
                            return parseLiveDetail(response.body(), streamerId, bno, authenticated);
                        });
    }

    private LiveDetail parseLiveDetail(
            String body, String streamerId, String bno, boolean authenticated) {
        try {
            JsonObject json = JsonFields.parseObject(body);

            if (!json.has("CHANNEL")) {
                int result = JsonFields.getInt(json, "RESULT", 0);
                if (result != 1) {
                    throw apiError(result, JsonFields.getString(json, "REASON", null));
                }
                throw new SOOPChatException("Response does not contain CHANNEL information");
            }

            JsonObject channel = json.getAsJsonObject("CHANNEL");

            int result = JsonFields.getInt(channel, "RESULT", JsonFields.getInt(json, "RESULT", 0));

            if (result == RESULT_ADULT_ONLY) {
                throw adultBroadcastError(streamerId, authenticated);
            }
            if (result != 1) {
                throw apiError(
                        result,
                        JsonFields.getString(
                                channel, "REASON", JsonFields.getString(json, "REASON", null)));
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

    /** REASON이 없으면 RESULT 코드라도 남긴다. */
    private static SOOPChatException apiError(int result, String reason) {
        return new SOOPChatException(
                "API error: " + (reason != null ? reason : "RESULT=" + result));
    }

    /**
     * 익명 요청이면 로그인 방법을, 로그인 요청이면 그 계정이 막힌 이유를 알려 준다. REST API({@code detail})를 직접 부른 경우와 채팅 연결에서 부른
     * 경우 모두 맞도록 쿠키를 넘기는 두 경로를 함께 적는다.
     */
    private static AdultBroadcastException adultBroadcastError(
            String streamerId, boolean authenticated) {
        if (authenticated) {
            return new AdultBroadcastException(
                    "The broadcast of "
                            + streamerId
                            + " is 19+ and the signed-in account is not allowed to view it."
                            + " The account may not be age-verified, or its login (AuthCookie)"
                            + " may have expired.");
        }
        return new AdultBroadcastException(
                "The broadcast of "
                        + streamerId
                        + " is 19+ and cannot be joined anonymously. Sign in with an age-verified"
                        + " account and pass its AuthCookie (SOOPChatConfig.Builder.authCookie()"
                        + " for chat, or detail(bid, bno, authCookie) for the REST API).");
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
