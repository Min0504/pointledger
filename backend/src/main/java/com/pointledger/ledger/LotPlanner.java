package com.pointledger.ledger;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * 로트 차감·복원 계획 — 순수 도메인 로직 (기획서 §11 Unit 레벨).
 *
 * 계획(어느 로트에서 얼마)과 적용(엔티티 변경·행 INSERT)을 분리한다:
 * 규칙은 DB 없이 단위 테스트하고, 트랜잭션 적용은 LedgerService가 담당한다.
 *
 * 규칙 요약:
 *   차감 — FIFO(만료 임박 순). 만료됐지만 아직 소멸(EXPIRE 기장) 전인 로트도
 *          차감 대상이다: 만료의 효력은 배치가 원장에 기장하는 순간 생긴다고
 *          정의해야 SUM(lot.remaining) == balance 불변식이 무조건식으로 유지된다.
 *          FIFO가 만료 임박분부터 쓰므로 고객에게 유리한 방향과도 일치한다.
 *   복원 — 소비의 역순(가장 늦게 만료되는 로트부터). 복원된 포인트가 가장
 *          오래 살아남는, 고객에게 유리한 방향이다. 이미 만료 시점이 지난
 *          로트로는 복원하지 않고 유예 로트(취소 시점 + 7일) 몫으로 돌린다 —
 *          복원 직후 다음 만료 배치에 소멸되는 "돌려준 척"을 막는다.
 */
public final class LotPlanner {

    /** 만료된 원 로트 몫의 복원 유예 기간 — 도메인 정책 (기획서 Phase 4 설계 질문) */
    public static final int GRACE_DAYS = 7;

    private LotPlanner() {
    }

    public record PlannedConsumption(PointLot lot, long amount) {
    }

    /** 복원 계획의 입력 — 소비 기록과 그 대상 로트의 쌍 */
    public record RestoreSource(LotConsumption consumption, PointLot lot) {
    }

    /** toGraceLot이면 원 로트가 만료돼 유예 로트 몫으로 복원한다 */
    public record PlannedRestore(RestoreSource source, long amount, boolean toGraceLot) {
    }

    /**
     * FIFO 차감 계획. lots는 만료 임박 순(expires_at, id) 정렬 전제 — 정렬은
     * 부분 인덱스(idx_lots_wallet_fifo)를 타는 조회 쿼리의 책임이다.
     *
     * @throws IllegalStateException 로트 합계가 부족할 때 — 잔액 검증을 통과한
     *         뒤라면 SUM(lot.remaining) == balance 불변식이 깨졌다는 뜻이므로
     *         복구 대상이 아니라 발견 즉시 실패해야 하는 상태다.
     */
    public static List<PlannedConsumption> planConsumption(List<PointLot> lots, long amount) {
        List<PlannedConsumption> plan = new ArrayList<>();
        long need = amount;
        for (PointLot lot : lots) {
            if (need == 0) {
                break;
            }
            long take = Math.min(lot.getRemaining(), need);
            if (take > 0) {
                plan.add(new PlannedConsumption(lot, take));
                need -= take;
            }
        }
        if (need > 0) {
            throw new IllegalStateException(
                    "로트 합계가 잔액보다 작다 — 불변식 위반: shortfall=" + need);
        }
        return plan;
    }

    /**
     * 취소 복원 계획. sources는 소비 순서(FIFO 소비 시점 순) 전제 — 역순으로
     * 걷어 늦게 만료되는 로트부터 되돌린다.
     *
     * @throws IllegalStateException 되돌릴 수 있는 합계가 부족할 때 — 취소 가능
     *         잔여 검증(원장 기준)을 통과한 뒤라면 소비 기록과 원장이 어긋났다는 뜻.
     */
    public static List<PlannedRestore> planRestore(
            List<RestoreSource> sources, long amount, Instant now) {
        List<PlannedRestore> plan = new ArrayList<>();
        long need = amount;
        for (int i = sources.size() - 1; i >= 0 && need > 0; i--) {
            RestoreSource source = sources.get(i);
            long take = Math.min(source.consumption().restorable(), need);
            if (take > 0) {
                plan.add(new PlannedRestore(source, take, source.lot().isExpiredAt(now)));
                need -= take;
            }
        }
        if (need > 0) {
            throw new IllegalStateException(
                    "소비 기록의 복원 가능 합계가 부족하다 — 원장과 불일치: shortfall=" + need);
        }
        return plan;
    }
}
