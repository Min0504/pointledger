package com.pointledger.batch;

import static org.assertj.core.api.Assertions.assertThat;

import com.pointledger.support.IntegrationTest;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.JobExecution;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/**
 * 대사 Job 통합 검증 (기획서 문제 5) — 3단 검사가 실제 불일치를 발견하는가,
 * 그리고 발견만 하고 고치지 않는가("탐지는 자동, 수정은 사람")가 대상이다.
 *
 * 각 검사는 일부러 데이터를 오염시켜 검증한다 — 대사의 존재 이유가
 * "안 틀린다"의 증명이 아니라 "틀렸을 때 반드시 드러난다"의 증명이기 때문.
 */
class ReconcileJobIntegrationTest extends IntegrationTest {

    static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");

    @Autowired
    private ReconcileBatchScheduler reconcileScheduler;

    @Autowired
    private SettleBatchScheduler settleScheduler;

    private final LocalDate today = LocalDate.now(SEOUL);
    private String adminToken;

    @BeforeEach
    void setUpFixtures() {
        adminToken = adminLogin();
        serverPost("/wallets", Map.of("userId", 42));
    }

    @Test
    @DisplayName("정합한 상태에선 아무 이슈도 만들지 않는다 — 대사의 기준선")
    void cleanStateProducesNoIssues() throws Exception {
        earn(5000, "ord-1");
        redeemAt(2000, "ord-2", createMerchant("카페 감마"));
        settleScheduler.launch(today);

        JobExecution execution = reconcileScheduler.launch(today, null);

        assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(issueCount()).isZero();
    }

    @Test
    @DisplayName("1단: 지갑 스냅샷이 원장 합과 어긋나면 SNAPSHOT_MISMATCH — 재실행은 미해결 이슈를 복제하지 않는다")
    void detectsSnapshotMismatchOnce() throws Exception {
        earn(5000, "ord-1");
        // 원장을 거치지 않은 잔액 오염 — 애플리케이션 밖에서 벌어진 사고를 흉내낸다
        jdbc.update("UPDATE wallets SET balance = balance + 777 WHERE user_id = 42");

        reconcileScheduler.launch(today, null);
        reconcileScheduler.launch(today, null); // 같은 불일치, 두 번째 발견

        List<Map<String, Object>> issues = jdbc.queryForList(
                "SELECT * FROM reconcile_issues WHERE issue_type = 'SNAPSHOT_MISMATCH'");
        assertThat(issues).hasSize(1); // NOT EXISTS 중복 방지 — 이슈 큐가 쓰레기가 되지 않는다
        assertThat(issues.get(0).get("expected")).isEqualTo(5000L); // 원장이 진실
        assertThat(issues.get(0).get("actual")).isEqualTo(5777L);   // 오염된 스냅샷
    }

    @Test
    @DisplayName("2단: 외부 기록과의 양방향 안티 조인 — 누락은 방향별로, 금액 불일치는 한 쌍으로 드러난다")
    void detectsBidirectionalGapsAgainstExternalFile(@TempDir Path tempDir) throws Exception {
        earn(5000, "ord-match");   // 외부 파일과 일치 — 이슈 없음
        earn(2000, "ord-diff");    // 외부는 2500이라 주장 — 금액 불일치
        earn(1000, "ord-onlyus"); // 외부 파일에 없음 — EXTERNAL_MISSING

        Path csv = tempDir.resolve("orders.csv");
        Files.writeString(csv, """
                ref_id,entry_type,user_id,amount
                ord-match,EARN,42,5000
                ord-diff,EARN,42,2500
                ord-onlythem,EARN,42,700
                """);

        JobExecution execution = reconcileScheduler.launch(today, csv.toString());
        assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);

        // 내부 원장에 없는 외부 기록: ord-onlythem(진짜 누락) + ord-diff(금액이 달라 매칭 실패)
        assertThat(issueRefIds("INTERNAL_MISSING"))
                .containsExactlyInAnyOrder("ord-onlythem", "ord-diff");
        // 외부 기록에 없는 내부 원장: ord-onlyus + ord-diff — 한 쌍이 금액 불일치의 신호다
        assertThat(issueRefIds("EXTERNAL_MISSING"))
                .containsExactlyInAnyOrder("ord-onlyus", "ord-diff");

        // 같은 파일 재적재 + 재검사 — 스테이징도 이슈도 불어나지 않는다
        reconcileScheduler.launch(today, csv.toString());
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM external_order_records", Long.class)).isEqualTo(3);
        assertThat(issueCount()).isEqualTo(4);
    }

    @Test
    @DisplayName("3단: 확정 후 도착한 거래로 정산서가 원장과 어긋나면 SETTLEMENT_MISMATCH")
    void detectsSettlementDrift() throws Exception {
        long merchant = createMerchant("서점 델타");
        earn(10_000, "ord-1");
        redeemAt(3000, "use-1", merchant);
        settleScheduler.launch(today);
        long settlementId = jdbc.queryForObject(
                "SELECT id FROM settlements WHERE merchant_id = ?", Long.class, merchant);
        adminPost("/admin/settlements/" + settlementId + "/confirm", Map.of(), adminToken);

        // 확정 뒤 같은 날 사용이 추가로 도착 — CONFIRMED는 재집계되지 않으므로 어긋난다
        redeemAt(500, "use-2", merchant);
        reconcileScheduler.launch(today, null);

        List<Map<String, Object>> issues = jdbc.queryForList(
                "SELECT * FROM reconcile_issues WHERE issue_type = 'SETTLEMENT_MISMATCH'");
        assertThat(issues).hasSize(1);
        assertThat(issues.get(0).get("expected")).isEqualTo(3500L); // 원장 재검산
        assertThat(issues.get(0).get("actual")).isEqualTo(3000L);   // 동결된 정산서
        assertThat(issues.get(0).get("merchant_id")).isEqualTo(merchant);
    }

    @Test
    @DisplayName("이슈 처리 흐름 — 근거(memo) 없인 닫을 수 없고, 닫힌 이슈는 다시 닫을 수 없고, 데이터가 그대로면 다시 발견된다")
    void issueResolutionFlow() throws Exception {
        earn(5000, "ord-1");
        jdbc.update("UPDATE wallets SET balance = balance + 777 WHERE user_id = 42");
        reconcileScheduler.launch(today, null);
        long issueId = jdbc.queryForObject(
                "SELECT id FROM reconcile_issues", Long.class);

        // 근거 없는 종결 거부 — 감사 관점에서 "확인했음" 없는 닫기는 무의미하다
        ResponseEntity<Map<String, Object>> noMemo = adminPost(
                "/admin/reconcile/issues/" + issueId + "/resolve", Map.of(), adminToken);
        assertThat(noMemo.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);

        ResponseEntity<Map<String, Object>> resolved = adminPost(
                "/admin/reconcile/issues/" + issueId + "/resolve",
                Map.of("memo", "지갑 수동 조작 확인, ADMIN_REVOKE로 정정 예정"), adminToken);
        assertThat(resolved.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resolved.getBody().get("resolvedBy")).isEqualTo(ADMIN_EMAIL);

        // 두 운영자가 같은 이슈를 집는 경합 — 나중 쪽은 409
        ResponseEntity<Map<String, Object>> again = adminPost(
                "/admin/reconcile/issues/" + issueId + "/resolve",
                Map.of("memo", "중복 처리"), adminToken);
        assertThat(again.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(again.getBody().get("code")).isEqualTo("ISSUE_ALREADY_RESOLVED");

        // 미해결 목록에서 사라진다
        ResponseEntity<Map<String, Object>> unresolvedList =
                adminGet("/admin/reconcile/issues?resolved=false", adminToken);
        assertThat((List<?>) unresolvedList.getBody().get("items")).isEmpty();

        // resolve는 검토 표시일 뿐 잔액을 고치지 않는다 — 불일치가 그대로면
        // 다음 대사가 새 이슈로 다시 발견한다 (자동 수정 금지의 이면)
        reconcileScheduler.launch(today, null);
        assertThat(issueCount()).isEqualTo(2);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM reconcile_issues WHERE resolved = false",
                Long.class)).isEqualTo(1);
    }

    // ── 픽스처 헬퍼 ─────────────────────────────────────────────────────

    private long createMerchant(String name) {
        return ((Number) adminPost("/admin/merchants", Map.of("name", name), adminToken)
                .getBody().get("id")).longValue();
    }

    private void earn(long amount, String refId) {
        ResponseEntity<Map<String, Object>> res = serverPost("/points/earn",
                Map.of("userId", 42, "amount", amount, "refType", "ORDER",
                        "refId", refId, "expireDays", 365));
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    }

    private void redeemAt(long amount, String refId, long merchantId) {
        ResponseEntity<Map<String, Object>> res = serverPost("/points/redeem",
                Map.of("userId", 42, "amount", amount, "refType", "ORDER",
                        "refId", refId, "merchantId", merchantId));
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    private long issueCount() {
        return jdbc.queryForObject("SELECT count(*) FROM reconcile_issues", Long.class);
    }

    private List<String> issueRefIds(String type) {
        return jdbc.queryForList(
                "SELECT ref_id FROM reconcile_issues WHERE issue_type = ?", String.class, type);
    }
}
