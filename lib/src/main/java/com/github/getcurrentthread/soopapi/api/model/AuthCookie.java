package com.github.getcurrentthread.soopapi.api.model;

public record AuthCookie(
        String userId,
        boolean success,
        String rawResponse,
        String authTicket,
        String abroadChk,
        String abroadVod,
        String bbsTicket,
        String rdb,
        String userTicket,
        String au,
        String au3rd,
        String ausa,
        String ausb) {

    public boolean isAuthenticated() {
        return success && authTicket != null && !authTicket.isEmpty();
    }

    /** 로그에 세션 자격 증명이 남지 않도록 티켓·쿠키 값은 가리고 rawResponse는 출력하지 않는다. */
    @Override
    public String toString() {
        return "AuthCookie[userId="
                + userId
                + ", success="
                + success
                + ", authTicket="
                + redact(authTicket)
                + ", abroadChk="
                + redact(abroadChk)
                + ", abroadVod="
                + redact(abroadVod)
                + ", bbsTicket="
                + redact(bbsTicket)
                + ", rdb="
                + redact(rdb)
                + ", userTicket="
                + redact(userTicket)
                + ", au="
                + redact(au)
                + ", au3rd="
                + redact(au3rd)
                + ", ausa="
                + redact(ausa)
                + ", ausb="
                + redact(ausb)
                + "]";
    }

    private static String redact(String value) {
        return value == null || value.isEmpty() ? "<empty>" : "<redacted>";
    }
}
