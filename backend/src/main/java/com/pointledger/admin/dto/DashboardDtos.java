package com.pointledger.admin.dto;

import java.time.LocalDate;
import java.util.List;

public final class DashboardDtos {

    private DashboardDtos() {
    }

    /** 유형별 오늘 집계 — 대시보드 카드 한 장의 데이터 */
    public record TypeStat(String type, long count, long amount) {
    }

    /**
     * circulating(유통 잔액)은 wallets.balance 합 — 원장이 진실이지만 대시보드는
     * 스냅샷 합으로 충분하다. 둘의 어긋남은 대사(1단)가 감시하는 영역이다.
     */
    public record DashboardResponse(
            LocalDate date,
            List<TypeStat> byType,
            long circulating,
            long wallets,
            long unresolvedIssues,
            long draftSettlements) {
    }
}
