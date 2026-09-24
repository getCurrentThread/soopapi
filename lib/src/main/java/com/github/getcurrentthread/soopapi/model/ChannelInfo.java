package com.github.getcurrentthread.soopapi.model;

public record ChannelInfo(
        String CHDOMAIN,
        String CHATNO,
        String FTK,
        String TITLE,
        String BJID,
        String CHPT,
        String BPS,
        String geoCC,
        String geoRC,
        String acptLang,
        String svcLang) {

    public ChannelInfo(
            String CHDOMAIN, String CHATNO, String FTK, String TITLE, String BJID, String CHPT) {
        this(CHDOMAIN, CHATNO, FTK, TITLE, BJID, CHPT, "", "", "", "", "");
    }

    private static final String SEPARATOR = "+" + "-".repeat(70) + "+";

    /** 채팅 입장 티켓(FTK)은 로그에 남지 않도록 가린다. */
    @Override
    public String toString() {
        return String.format(
                "%s%n  CHDOMAIN: %s%n  CHATNO: %s%n  FTK: %s%n  TITLE: %s%n  BJID: %s%n  CHPT: %s%n%s",
                SEPARATOR,
                CHDOMAIN,
                CHATNO,
                FTK == null || FTK.isEmpty() ? "<empty>" : "<redacted>",
                TITLE,
                BJID,
                CHPT,
                SEPARATOR);
    }
}
