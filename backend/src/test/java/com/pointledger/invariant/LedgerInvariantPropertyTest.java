package com.pointledger.invariant;

import static org.assertj.core.api.Assertions.assertThat;

import com.pointledger.support.IntegrationTest;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/**
 * 불변식 프로퍼티 테스트 (기획서 §11) — 개별 시나리오가 아니라 "어떤 연산
 * 순서로도 깨지지 않아야 하는 문장"을 검증한다. 이 테스트 하나가 시스템
 * 전체의 정합성 증명이다:
 *
 *   1. wallet.balance == SUM(원장 부호 합)   — 잔액은 원장의 파생값
 *   2. wallet.balance == SUM(lot.remaining)  — 로트는 잔액의 분해
 *   3. 모든 로트: 0 <= remaining <= initial_amount
 *   4. 모든 소비: 0 <= restored <= amount
 *   5. 차감 원장(REDEEM/ADMIN_REVOKE) = 소비 기록 합과 일치
 *   6. CANCEL 누적 != 원본 REDEEM 초과 불가
 *
 * 실패 시 seed를 출력한다 — 같은 seed로 재실행하면 같은 순서가 재현된다.
 */
class LedgerInvariantPropertyTest extends IntegrationTest {

    private static final int OPERATIONS = 1000;

    private final long seed = System.nanoTime();
    private final Random rnd = new Random(seed);
    private final List<Long> redeemEntries = new ArrayList<>();
    private final List<Long> lotIds = new ArrayList<>();

    @Test
    @DisplayName("무작위 연산 1,000회 후에도 원장·잔액·로트·소비 불변식이 전부 성립한다")
    void invariantsHoldUnderRandomOperationSequence() {
        serverPost("/wallets", Map.of("userId", 42));
        String token = adminLogin();

        for (int i = 1; i <= OPERATIONS; i++) {
            int dice = rnd.nextInt(100);
            if (dice < 35) {
                earn(i);
            } else if (dice < 70) {
                redeem(i);
            } else if (dice < 88) {
                cancel();
            } else if (dice < 93) {
                grant(token, i);
            } else if (dice < 98) {
                revoke(token);
            } else {
                ageRandomLot(); // 만료-미소멸 소비·유예 복원 경로를 무작위로 끼워 넣는다
            }
            if (i % 250 == 0) {
                assertInvariants(i);
            }
        }
        assertInvariants(OPERATIONS);
    }

    // ── 연산 — 예상된 도메인 거절(409)은 허용, 그 외 실패는 즉시 중단 ─────────

    private void earn(int i) {
        ResponseEntity<Map<String, Object>> res = serverPost("/points/earn",
                Map.of("userId", 42, "amount", 1 + rnd.nextInt(5000),
                        "refType", "ORDER", "refId", "op-" + i,
                        "expireDays", 1 + rnd.nextInt(60)));
        expect(res, HttpStatus.CREATED);
        lotIds.add(asLong(res.getBody().get("lotId")));
    }

    private void redeem(int i) {
        ResponseEntity<Map<String, Object>> res = serverPost("/points/redeem",
                Map.of("userId", 42, "amount", 1 + rnd.nextInt(3000),
                        "refType", "ORDER", "refId", "op-" + i));
        if (res.getStatusCode() == HttpStatus.OK) {
            redeemEntries.add(asLong(res.getBody().get("entryId")));
            return;
        }
        expectRejection(res, "INSUFFICIENT_BALANCE");
    }

    private void cancel() {
        if (redeemEntries.isEmpty()) {
            return;
        }
        long entryId = redeemEntries.get(rnd.nextInt(redeemEntries.size()));
        // 전액(바디 없음)과 무작위 부분 취소를 섞는다 — 초과 시도도 일부러 남긴다
        Map<String, ?> body = rnd.nextBoolean() ? null : Map.of("amount", 1 + rnd.nextInt(3000));
        ResponseEntity<Map<String, Object>> res =
                serverPost("/points/redeem/" + entryId + "/cancel", body);
        if (res.getStatusCode() != HttpStatus.OK) {
            expectRejection(res, "CANCEL_EXCEEDS_REMAINING");
        }
    }

    private void grant(String token, int i) {
        ResponseEntity<Map<String, Object>> res = adminPost("/admin/points/grant",
                Map.of("userId", 42, "amount", 1 + rnd.nextInt(3000),
                        "reason", "property-" + i, "expireDays", 1 + rnd.nextInt(30)), token);
        expect(res, HttpStatus.CREATED);
        lotIds.add(asLong(res.getBody().get("lotId")));
    }

    private void revoke(String token) {
        ResponseEntity<Map<String, Object>> res = adminPost("/admin/points/revoke",
                Map.of("userId", 42, "amount", 1 + rnd.nextInt(2000),
                        "reason", "property-revoke"), token);
        if (res.getStatusCode() != HttpStatus.OK) {
            expectRejection(res, "INSUFFICIENT_BALANCE");
        }
    }

    private void ageRandomLot() {
        if (lotIds.isEmpty()) {
            return;
        }
        jdbc.update("UPDATE point_lots SET expires_at = now() - interval '1 hour' WHERE id = ?",
                lotIds.get(rnd.nextInt(lotIds.size())));
    }

    // ── 불변식 — 전부 DB에서 직접 검증한다 ────────────────────────────────

    private void assertInvariants(int afterOps) {
        String ctx = "seed=" + seed + ", ops=" + afterOps;

        long balance = query("SELECT balance FROM wallets WHERE user_id = 42");
        long ledgerSum = query("""
                SELECT COALESCE(SUM(CASE WHEN type IN ('EARN','CANCEL','ADMIN_GRANT')
                                         THEN amount ELSE -amount END), 0)
                FROM ledger_entries
                """);
        long lotSum = query("SELECT COALESCE(SUM(remaining), 0) FROM point_lots");

        assertThat(balance).as("잔액 == 원장 부호 합 (%s)", ctx).isEqualTo(ledgerSum);
        assertThat(balance).as("잔액 == 로트 잔여 합 (%s)", ctx).isEqualTo(lotSum);

        assertThat(query("""
                SELECT count(*) FROM point_lots
                WHERE remaining < 0 OR remaining > initial_amount
                """)).as("로트 범위 위반 0건 (%s)", ctx).isZero();

        assertThat(query("""
                SELECT count(*) FROM lot_consumptions
                WHERE restored < 0 OR restored > amount
                """)).as("소비 복원 범위 위반 0건 (%s)", ctx).isZero();

        assertThat(query("""
                SELECT count(*) FROM ledger_entries e
                WHERE e.type IN ('REDEEM', 'ADMIN_REVOKE')
                  AND e.amount <> (SELECT COALESCE(SUM(c.amount), 0)
                                   FROM lot_consumptions c WHERE c.consuming_entry_id = e.id)
                """)).as("차감 원장 == 소비 기록 합 (%s)", ctx).isZero();

        assertThat(query("""
                SELECT count(*) FROM ledger_entries r
                WHERE r.type = 'REDEEM'
                  AND r.amount < (SELECT COALESCE(SUM(c.amount), 0)
                                  FROM ledger_entries c WHERE c.related_entry_id = r.id)
                """)).as("CANCEL 누적 <= 원본 REDEEM (%s)", ctx).isZero();
    }

    private void expect(ResponseEntity<Map<String, Object>> res, HttpStatus status) {
        assertThat(res.getStatusCode())
                .as("seed=%d, body=%s", seed, res.getBody())
                .isEqualTo(status);
    }

    private void expectRejection(ResponseEntity<Map<String, Object>> res, String allowedCode) {
        assertThat(res.getStatusCode().value())
                .as("seed=%d, body=%s", seed, res.getBody())
                .isEqualTo(HttpStatus.CONFLICT.value());
        assertThat(res.getBody().get("code"))
                .as("seed=%d, body=%s", seed, res.getBody())
                .isEqualTo(allowedCode);
    }

    private long query(String sql) {
        return jdbc.queryForObject(sql, Long.class);
    }

    private long asLong(Object value) {
        return ((Number) value).longValue();
    }
}
