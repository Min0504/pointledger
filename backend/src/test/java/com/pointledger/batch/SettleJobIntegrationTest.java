package com.pointledger.batch;

import static org.assertj.core.api.Assertions.assertThat;

import com.pointledger.support.IntegrationTest;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.JobExecution;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/**
 * 정산 Job 통합 검증 (기획서 문제 5) — 가맹점별 집계, 재실행 멱등성(늦은 원장
 * 반영), 확정 동결과 익일 차감 이월이 대상이다.
 *
 * 멱등성의 근거가 배치 코드가 아니라 스키마(UNIQUE 두 개)라는 점을 재실행
 * 테스트가 보여준다 — 같은 날짜를 몇 번 돌려도 정산서는 하나다.
 */
class SettleJobIntegrationTest extends IntegrationTest {

    static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");

    @Autowired
    private SettleBatchScheduler settleScheduler;

    private final LocalDate today = LocalDate.now(SEOUL);
    private String adminToken;
    private long merchantA;
    private long merchantB;

    @BeforeEach
    void setUpFixtures() {
        adminToken = adminLogin();
        merchantA = createMerchant("카페 알파");
        merchantB = createMerchant("서점 베타");
        serverPost("/wallets", Map.of("userId", 42));
        earn(50_000);
    }

    @Test
    @DisplayName("가맹점별로 사용을 집계한다 — 가맹점 미지정 사용은 정산 대상이 아니다")
    void aggregatesRedeemsByMerchant() throws Exception {
        redeem(3000, "use-a1", merchantA);
        redeem(2000, "use-a2", merchantA);
        redeem(1000, "use-b1", merchantB);
        redeem(500, "use-none", null); // 정산 제외 — 라인에 나타나면 안 된다

        JobExecution execution = settleScheduler.launch(today);

        assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(settlementTotal(merchantA)).isEqualTo(5000);
        assertThat(settlementTotal(merchantB)).isEqualTo(1000);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM settlement_lines", Long.class)).isEqualTo(3);

        // 운영자 조회 API에도 같은 사실이 가맹점 이름과 함께 보인다
        ResponseEntity<Map<String, Object>> res =
                adminGet("/admin/settlements?settleDate=" + today, adminToken);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> items = (List<Map<String, Object>>) res.getBody().get("items");
        assertThat(items).hasSize(2);
        assertThat(items).extracting(i -> i.get("merchantName"))
                .containsExactlyInAnyOrder("카페 알파", "서점 베타");
        assertThat(items).allMatch(i -> "DRAFT".equals(i.get("status")));
    }

    @Test
    @DisplayName("재실행은 오류가 아니라 재집계다 — 늦게 도착한 취소가 DRAFT 총액에 반영되고 정산서는 하나로 유지된다")
    void rerunRefreshesDraftWithLateCancel() throws Exception {
        long redeemEntry = redeem(3000, "use-late", merchantA);
        settleScheduler.launch(today);
        assertThat(settlementTotal(merchantA)).isEqualTo(3000);

        cancel(redeemEntry, 1000L);
        JobExecution rerun = settleScheduler.launch(today);

        assertThat(rerun.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        // UNIQUE (merchant_id, settle_date) — 재실행이 두 번째 정산서를 만들 수 없다
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM settlements WHERE merchant_id = ?",
                Long.class, merchantA)).isEqualTo(1);
        // REDEEM +3000, CANCEL −1000 두 라인 — 총액은 라인 합에서 파생된다
        assertThat(settlementTotal(merchantA)).isEqualTo(2000);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM settlement_lines", Long.class)).isEqualTo(2);
    }

    @Test
    @DisplayName("확정된 정산서는 동결된다 — 다음 날의 취소는 그날의 차감(음수) 정산으로 이월된다")
    void confirmedSettlementIsFrozenAndCancelCarriesOver() throws Exception {
        // 어제의 사용을 시뮬레이션 — append-only 트리거를 잠시 내리고 백데이트한다.
        // 운영에선 불가능한 조작이지만, 테스트가 "어제"를 만들 유일한 통로다.
        LocalDate yesterday = today.minusDays(1);
        long redeemEntry = redeem(3000, "use-yday", merchantA);
        backdate(redeemEntry, yesterday);

        settleScheduler.launch(yesterday);
        long settlementId = jdbc.queryForObject("""
                SELECT id FROM settlements WHERE merchant_id = ? AND settle_date = ?
                """, Long.class, merchantA, java.sql.Date.valueOf(yesterday));
        ResponseEntity<Map<String, Object>> confirmed = adminPost(
                "/admin/settlements/" + settlementId + "/confirm", Map.of(), adminToken);
        assertThat(confirmed.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(confirmed.getBody().get("status")).isEqualTo("CONFIRMED");
        assertThat(confirmed.getBody().get("confirmedBy")).isEqualTo(ADMIN_EMAIL);

        // 확정 후 전액 취소 도착 (취소의 created_at은 오늘)
        cancel(redeemEntry, null);

        // 어제 재정산 — CONFIRMED는 재집계 대상이 아니므로 총액이 변하지 않는다
        settleScheduler.launch(yesterday);
        assertThat(jdbc.queryForObject(
                "SELECT total_amount FROM settlements WHERE id = ?",
                Long.class, settlementId)).isEqualTo(3000);
        assertThat(jdbc.queryForObject(
                "SELECT status FROM settlements WHERE id = ?",
                String.class, settlementId)).isEqualTo("CONFIRMED");

        // 오늘 정산 — 취소가 음수 라인으로 잡혀 차감 정산서가 된다
        settleScheduler.launch(today);
        assertThat(jdbc.queryForObject("""
                SELECT total_amount FROM settlements
                WHERE merchant_id = ? AND settle_date = ?
                """, Long.class, merchantA, java.sql.Date.valueOf(today))).isEqualTo(-3000);

        // 한 거래는 한 정산서에만 — REDEEM 라인은 어제 정산서에 그대로 남아 있다
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM settlement_lines WHERE ledger_entry_id = ?",
                Long.class, redeemEntry)).isEqualTo(1);
    }

    @Test
    @DisplayName("확정은 멱등이다 — 두 번 눌러도 최초 확정 기록이 유지된다")
    void confirmIsIdempotent() throws Exception {
        redeem(1000, "use-conf", merchantA);
        settleScheduler.launch(today);
        long settlementId = jdbc.queryForObject(
                "SELECT id FROM settlements WHERE merchant_id = ?", Long.class, merchantA);

        ResponseEntity<Map<String, Object>> first = adminPost(
                "/admin/settlements/" + settlementId + "/confirm", Map.of(), adminToken);
        ResponseEntity<Map<String, Object>> second = adminPost(
                "/admin/settlements/" + settlementId + "/confirm", Map.of(), adminToken);

        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(second.getBody().get("confirmedAt")).isEqualTo(first.getBody().get("confirmedAt"));
    }

    @Test
    @DisplayName("모르는(또는 비활성) 가맹점으로는 사용을 기록할 수 없다 — 정산 입력을 문에서 거른다")
    void rejectsUnknownMerchantOnRedeem() {
        ResponseEntity<Map<String, Object>> res = serverPost("/points/redeem",
                Map.of("userId", 42, "amount", 1000, "refType", "ORDER",
                        "refId", "use-bad", "merchantId", 9999));

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(res.getBody().get("code")).isEqualTo("MERCHANT_NOT_FOUND");
    }

    // ── 픽스처 헬퍼 ─────────────────────────────────────────────────────

    private long createMerchant(String name) {
        ResponseEntity<Map<String, Object>> res =
                adminPost("/admin/merchants", Map.of("name", name), adminToken);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return ((Number) res.getBody().get("id")).longValue();
    }

    private void earn(long amount) {
        ResponseEntity<Map<String, Object>> res = serverPost("/points/earn",
                Map.of("userId", 42, "amount", amount, "refType", "ORDER",
                        "refId", "earn-" + amount, "expireDays", 365));
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    }

    private long redeem(long amount, String refId, Long merchantId) {
        Map<String, Object> body = merchantId == null
                ? Map.of("userId", 42, "amount", amount, "refType", "ORDER", "refId", refId)
                : Map.of("userId", 42, "amount", amount, "refType", "ORDER", "refId", refId,
                        "merchantId", merchantId);
        ResponseEntity<Map<String, Object>> res = serverPost("/points/redeem", body);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        return ((Number) res.getBody().get("entryId")).longValue();
    }

    private void cancel(long entryId, Long amount) {
        ResponseEntity<Map<String, Object>> res = serverPost(
                "/points/redeem/" + entryId + "/cancel",
                amount == null ? Map.of() : Map.of("amount", amount));
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    private long settlementTotal(long merchantId) {
        return jdbc.queryForObject("""
                SELECT total_amount FROM settlements
                WHERE merchant_id = ? AND settle_date = ?
                """, Long.class, merchantId, java.sql.Date.valueOf(today));
    }

    private void backdate(long entryId, LocalDate date) {
        jdbc.execute("ALTER TABLE ledger_entries DISABLE TRIGGER trg_ledger_append_only");
        jdbc.update("UPDATE ledger_entries SET created_at = ? WHERE id = ?",
                Timestamp.from(date.atTime(12, 0).atZone(SEOUL).toInstant()), entryId);
        jdbc.execute("ALTER TABLE ledger_entries ENABLE TRIGGER trg_ledger_append_only");
    }
}
