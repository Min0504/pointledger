package com.pointledger.ledger;

import static org.assertj.core.api.Assertions.assertThat;

import com.pointledger.support.IntegrationTest;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/**
 * FIFO 차감 통합 검증 — "어느 적립분에서 나갔는가"가 응답(consumedLots)과
 * 소비 기록(lot_consumptions)에 남는지, 만료 임박 순서가 지켜지는지 (기획서 §2, §8).
 */
class LotFifoIntegrationTest extends IntegrationTest {

    @BeforeEach
    void createWallet() {
        serverPost("/wallets", Map.of("userId", 42));
    }

    private long earn(long amount, int expireDays, String refId) {
        ResponseEntity<Map<String, Object>> res = serverPost("/points/earn",
                Map.of("userId", 42, "amount", amount, "refType", "ORDER",
                        "refId", refId, "expireDays", expireDays));
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return ((Number) res.getBody().get("lotId")).longValue();
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> consumedLots(ResponseEntity<Map<String, Object>> res) {
        return (List<Map<String, Object>>) res.getBody().get("consumedLots");
    }

    @Test
    @DisplayName("적립 순서가 아니라 만료 임박 순으로 차감한다 — consumedLots가 근거를 보고한다")
    void consumesByExpiryNotByEarnOrder() {
        long lateExpiry = earn(2000, 30, "ord-late");   // 먼저 적립했지만 만료는 늦다
        long earlyExpiry = earn(5000, 10, "ord-early"); // 나중 적립했지만 만료 임박

        ResponseEntity<Map<String, Object>> res = serverPost("/points/redeem",
                Map.of("userId", 42, "amount", 6000, "refType", "ORDER", "refId", "use-1"));

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        List<Map<String, Object>> consumed = consumedLots(res);
        assertThat(consumed).hasSize(2);
        assertThat(((Number) consumed.get(0).get("lotId")).longValue()).isEqualTo(earlyExpiry);
        assertThat(consumed.get(0).get("amount")).isEqualTo(5000); // 임박분 전량 소진 후
        assertThat(((Number) consumed.get(1).get("lotId")).longValue()).isEqualTo(lateExpiry);
        assertThat(consumed.get(1).get("amount")).isEqualTo(1000); // 다음 로트로

        // 로트 잔여와 소비 기록이 응답과 일치한다
        assertThat(lotRemaining(earlyExpiry)).isZero();
        assertThat(lotStatus(earlyExpiry)).isEqualTo("EXHAUSTED");
        assertThat(lotRemaining(lateExpiry)).isEqualTo(1000);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM lot_consumptions", Long.class)).isEqualTo(2);
    }

    @Test
    @DisplayName("만료 시점은 지났지만 아직 소멸(EXPIRE 기장) 전인 로트도 차감 대상이다")
    void expiredButUnsweptLotIsStillConsumable() {
        long aged = earn(1000, 30, "ord-aged");
        long fresh = earn(1000, 30, "ord-fresh");
        // 만료 배치(Phase 5)가 아직 다녀가지 않은 상태를 만든다
        jdbc.update("UPDATE point_lots SET expires_at = now() - interval '1 day' WHERE id = ?", aged);

        ResponseEntity<Map<String, Object>> res = serverPost("/points/redeem",
                Map.of("userId", 42, "amount", 1500, "refType", "ORDER", "refId", "use-2"));

        // 만료의 효력은 EXPIRE가 원장에 기장되는 순간이라고 정의한다 — 그래야
        // SUM(lot.remaining) == balance 불변식이 무조건식으로 유지되고,
        // FIFO가 만료 지난 분부터 소진하므로 고객에게도 유리하다
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        List<Map<String, Object>> consumed = consumedLots(res);
        assertThat(((Number) consumed.get(0).get("lotId")).longValue()).isEqualTo(aged);
        assertThat(consumed.get(0).get("amount")).isEqualTo(1000);
        assertThat(((Number) consumed.get(1).get("lotId")).longValue()).isEqualTo(fresh);
        assertThat(consumed.get(1).get("amount")).isEqualTo(500);
    }

    private long lotRemaining(long lotId) {
        return jdbc.queryForObject(
                "SELECT remaining FROM point_lots WHERE id = ?", Long.class, lotId);
    }

    private String lotStatus(long lotId) {
        return jdbc.queryForObject(
                "SELECT status FROM point_lots WHERE id = ?", String.class, lotId);
    }
}
