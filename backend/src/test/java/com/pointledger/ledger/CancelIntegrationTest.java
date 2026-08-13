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
 * 사용 취소 통합 검증 (기획서 §2 — "이미 만료된 적립분으로의 복원은? 부분 취소는?").
 *
 * 채택한 도메인 규칙:
 *   - 복원은 소비의 역순(늦게 만료되는 로트부터) — 복원분이 가장 오래 산다
 *   - 원 로트가 만료됐으면 유예 로트(취소 +7일)로 — "돌려준 척" 방지
 *   - 부분 취소 누적은 lot_consumptions.restored가 추적, 초과는 409
 *   - 원장은 append-only — 취소는 삭제가 아니라 CANCEL 엔트리 추가 + related_entry_id
 */
class CancelIntegrationTest extends IntegrationTest {

    @BeforeEach
    void createWallet() {
        serverPost("/wallets", Map.of("userId", 42));
    }

    private long earn(long amount, int expireDays, String refId) {
        return ((Number) serverPost("/points/earn",
                Map.of("userId", 42, "amount", amount, "refType", "ORDER",
                        "refId", refId, "expireDays", expireDays))
                .getBody().get("lotId")).longValue();
    }

    private long redeem(long amount, String refId) {
        ResponseEntity<Map<String, Object>> res = serverPost("/points/redeem",
                Map.of("userId", 42, "amount", amount, "refType", "ORDER", "refId", refId));
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        return ((Number) res.getBody().get("entryId")).longValue();
    }

    private ResponseEntity<Map<String, Object>> cancel(long entryId, Long amount) {
        Map<String, ?> body = amount == null ? null : Map.of("amount", amount);
        return serverPost("/points/redeem/" + entryId + "/cancel", body);
    }

    @Test
    @DisplayName("전액 취소 — 잔액·로트가 사용 전으로 돌아가고 CANCEL이 원본을 가리킨다")
    void fullCancelRestoresEverything() {
        long lot = earn(5000, 30, "ord-1");
        long redeemEntry = redeem(3000, "use-1");

        ResponseEntity<Map<String, Object>> res = cancel(redeemEntry, null);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(res.getBody()).containsEntry("balanceAfter", 5000);
        assertThat(res.getBody().get("graceLot")).isNull();
        assertThat(lotRemaining(lot)).isEqualTo(5000);
        assertThat(lotStatus(lot)).isEqualTo("ACTIVE");

        // 원장에는 REDEEM이 남고 CANCEL이 추가된다 — 기록은 고치지 않는다
        assertThat(jdbc.queryForObject(
                "SELECT related_entry_id FROM ledger_entries WHERE type = 'CANCEL'",
                Long.class)).isEqualTo(redeemEntry);
        assertThat(jdbc.queryForObject(
                "SELECT restored FROM lot_consumptions", Long.class)).isEqualTo(3000);
    }

    @Test
    @DisplayName("부분 취소는 늦게 만료되는 로트부터 복원한다 — 복원분이 가장 오래 살도록")
    void partialCancelRestoresInReverseOrder() {
        long earlyExpiry = earn(2000, 10, "ord-a"); // FIFO로 먼저 소비될 로트
        long lateExpiry = earn(5000, 30, "ord-b");
        long redeemEntry = redeem(3000, "use-1");   // early 2000 + late 1000 소비

        ResponseEntity<Map<String, Object>> res = cancel(redeemEntry, 1500L);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> restored =
                (List<Map<String, Object>>) res.getBody().get("restoredLots");
        assertThat(restored).hasSize(2);
        // 역순: 늦게 소비된(늦게 만료되는) late부터 1000, 나머지 500은 early로
        assertThat(((Number) restored.get(0).get("lotId")).longValue()).isEqualTo(lateExpiry);
        assertThat(restored.get(0).get("amount")).isEqualTo(1000);
        assertThat(((Number) restored.get(1).get("lotId")).longValue()).isEqualTo(earlyExpiry);
        assertThat(restored.get(1).get("amount")).isEqualTo(500);

        assertThat(lotRemaining(lateExpiry)).isEqualTo(5000);  // 4000 + 1000 복원
        assertThat(lotRemaining(earlyExpiry)).isEqualTo(500);  // 0 + 500 복원
        assertThat(lotStatus(earlyExpiry)).isEqualTo("ACTIVE"); // EXHAUSTED에서 되살아남
    }

    @Test
    @DisplayName("부분 취소 누적이 원 사용액을 넘으면 409 — 취소 가능 잔여를 알려준다")
    void cumulativePartialCancelCannotExceedOriginal() {
        earn(5000, 30, "ord-1");
        long redeemEntry = redeem(3000, "use-1");

        assertThat(cancel(redeemEntry, 2000L).getStatusCode()).isEqualTo(HttpStatus.OK);

        ResponseEntity<Map<String, Object>> exceeded = cancel(redeemEntry, 2000L);
        assertThat(exceeded.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(exceeded.getBody()).containsEntry("code", "CANCEL_EXCEEDS_REMAINING");
        @SuppressWarnings("unchecked")
        Map<String, Object> details = (Map<String, Object>) exceeded.getBody().get("details");
        assertThat(details).containsEntry("cancellable", 1000).containsEntry("requested", 2000);

        // 남은 1,000은 여전히 취소 가능하다
        assertThat(cancel(redeemEntry, 1000L).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(walletBalance()).isEqualTo(5000);

        // 전액 소진 후의 전액(바디 없음) 재취소도 409다 — 0원 CANCEL이 기장되면 안 된다
        ResponseEntity<Map<String, Object>> exhausted = cancel(redeemEntry, null);
        assertThat(exhausted.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(exhausted.getBody()).containsEntry("code", "CANCEL_EXCEEDS_REMAINING");
    }

    @Test
    @DisplayName("원 로트가 만료됐으면 제자리 복원 대신 유예 로트(+7일)를 만든다")
    void expiredLotPortionRestoresToGraceLot() {
        long lot = earn(1000, 30, "ord-1");
        long redeemEntry = redeem(1000, "use-1");
        // 취소 전에 원 로트가 만료 시점을 지났다
        jdbc.update("UPDATE point_lots SET expires_at = now() - interval '1 hour' WHERE id = ?", lot);

        ResponseEntity<Map<String, Object>> res = cancel(redeemEntry, null);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat((List<?>) res.getBody().get("restoredLots")).isEmpty();
        @SuppressWarnings("unchecked")
        Map<String, Object> grace = (Map<String, Object>) res.getBody().get("graceLot");
        assertThat(grace.get("amount")).isEqualTo(1000);
        long graceLotId = ((Number) grace.get("lotId")).longValue();

        // 원 로트는 그대로 비어 있고, 유예 로트가 CANCEL 엔트리에서 태어났다
        assertThat(lotRemaining(lot)).isZero();
        assertThat(lotRemaining(graceLotId)).isEqualTo(1000);
        assertThat(jdbc.queryForObject("""
                SELECT l.type FROM point_lots p JOIN ledger_entries l ON l.id = p.earn_entry_id
                WHERE p.id = ?
                """, String.class, graceLotId)).isEqualTo("CANCEL");
        // 유예 기간은 취소 시점 + 7일
        assertThat(jdbc.queryForObject("""
                SELECT expires_at BETWEEN now() + interval '6 days 23 hours'
                                      AND now() + interval '7 days 1 hour'
                FROM point_lots WHERE id = ?
                """, Boolean.class, graceLotId)).isTrue();
        assertThat(walletBalance()).isEqualTo(1000);
    }

    @Test
    @DisplayName("REDEEM이 아닌 기록은 취소할 수 없다 — 422 / 없는 기록은 404")
    void onlyRedeemEntriesAreCancellable() {
        earn(1000, 30, "ord-1");
        long earnEntry = jdbc.queryForObject(
                "SELECT id FROM ledger_entries WHERE type = 'EARN'", Long.class);

        ResponseEntity<Map<String, Object>> notRedeem = cancel(earnEntry, null);
        assertThat(notRedeem.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(notRedeem.getBody()).containsEntry("code", "NOT_CANCELLABLE");

        ResponseEntity<Map<String, Object>> missing = cancel(99999L, null);
        assertThat(missing.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(missing.getBody()).containsEntry("code", "ENTRY_NOT_FOUND");
    }

    @Test
    @DisplayName("같은 키의 취소 재시도는 재생된다 — 이중 복원 없음")
    void cancelIsIdempotent() {
        earn(5000, 30, "ord-1");
        long redeemEntry = redeem(3000, "use-1");

        String key = "cancel-use-1";
        ResponseEntity<Map<String, Object>> first =
                serverPost("/points/redeem/" + redeemEntry + "/cancel", null, key);
        ResponseEntity<Map<String, Object>> retry =
                serverPost("/points/redeem/" + redeemEntry + "/cancel", null, key);

        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(retry.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(retry.getBody()).isEqualTo(first.getBody());
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM ledger_entries WHERE type = 'CANCEL'", Long.class))
                .isEqualTo(1);
        assertThat(walletBalance()).isEqualTo(5000);
    }

    private long lotRemaining(long lotId) {
        return jdbc.queryForObject(
                "SELECT remaining FROM point_lots WHERE id = ?", Long.class, lotId);
    }

    private String lotStatus(long lotId) {
        return jdbc.queryForObject(
                "SELECT status FROM point_lots WHERE id = ?", String.class, lotId);
    }

    private long walletBalance() {
        return jdbc.queryForObject("SELECT balance FROM wallets WHERE user_id = 42", Long.class);
    }
}
