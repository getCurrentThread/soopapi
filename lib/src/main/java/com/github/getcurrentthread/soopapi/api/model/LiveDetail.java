package com.github.getcurrentthread.soopapi.api.model;

public record LiveDetail(
        String bjId,
        String bno,
        String title,
        String chatDomain,
        String chatNo,
        String ftk,
        String chatPort,
        int result,
        String bps,
        String geoCC,
        String geoRC,
        String acptLang,
        String svcLang) {

    public LiveDetail(
            String bjId,
            String bno,
            String title,
            String chatDomain,
            String chatNo,
            String ftk,
            String chatPort,
            int result) {
        this(bjId, bno, title, chatDomain, chatNo, ftk, chatPort, result, "", "", "", "", "");
    }

    /** 채팅 입장 티켓(FTK)은 로그에 남지 않도록 가린다. */
    @Override
    public String toString() {
        return "LiveDetail[bjId="
                + bjId
                + ", bno="
                + bno
                + ", title="
                + title
                + ", chatDomain="
                + chatDomain
                + ", chatNo="
                + chatNo
                + ", ftk="
                + (ftk == null || ftk.isEmpty() ? "<empty>" : "<redacted>")
                + ", chatPort="
                + chatPort
                + ", result="
                + result
                + ", bps="
                + bps
                + ", geoCC="
                + geoCC
                + ", geoRC="
                + geoRC
                + ", acptLang="
                + acptLang
                + ", svcLang="
                + svcLang
                + "]";
    }
}
