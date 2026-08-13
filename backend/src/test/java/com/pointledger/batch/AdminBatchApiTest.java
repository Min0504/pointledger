package com.pointledger.batch;

import static org.assertj.core.api.Assertions.assertThat;

import com.pointledger.support.IntegrationTest;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/**
 * 배치 수동 기동 API — 스케줄을 놓친 날짜의 소급 실행이 용도다.
 * 실행 성패는 HTTP 상태가 아니라 응답의 status(BatchStatus)로 전달된다 —
 * "기동은 됐고 스텝이 실패했다"와 "기동 자체가 거부됐다"는 다른 사건이다.
 */
class AdminBatchApiTest extends IntegrationTest {

    static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");

    private final LocalDate today = LocalDate.now(SEOUL);
    private String adminToken;

    @BeforeEach
    void login() {
        adminToken = adminLogin();
    }

    @Test
    @DisplayName("운영자는 지난 날짜의 정산을 수동 기동할 수 있다")
    void runsSettleManually() {
        ResponseEntity<Map<String, Object>> res = adminPost("/admin/batch/settle/run",
                Map.of("settleDate", today.toString()), adminToken);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(res.getBody().get("jobName")).isEqualTo(SettleJobConfig.JOB_NAME);
        assertThat(res.getBody().get("status")).isEqualTo("COMPLETED");
    }

    @Test
    @DisplayName("만료 Job의 완료 재수행은 SKIPPED로 응답한다 — JobInstance 멱등성이 API에서도 보인다")
    void expireRerunIsSkipped() {
        ResponseEntity<Map<String, Object>> first = adminPost("/admin/batch/expire/run",
                Map.of("asOf", today.toString()), adminToken);
        ResponseEntity<Map<String, Object>> second = adminPost("/admin/batch/expire/run",
                Map.of("asOf", today.toString()), adminToken);

        assertThat(first.getBody().get("status")).isEqualTo("COMPLETED");
        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(second.getBody().get("status")).isEqualTo("SKIPPED");
        assertThat(second.getBody().get("exitCode")).isEqualTo("ALREADY_COMPLETE");
    }

    @Test
    @DisplayName("존재하지 않는 외부 파일이면 대사 Job은 FAILED 상태를 그대로 보고한다")
    void reportsFailedExecutionHonestly() {
        ResponseEntity<Map<String, Object>> res = adminPost("/admin/batch/reconcile/run",
                Map.of("reconcileDate", today.toString(),
                        "externalFile", "/nonexistent/orders.csv"), adminToken);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(res.getBody().get("status")).isEqualTo("FAILED");
    }

    @Test
    @DisplayName("서버 간 API 키로는 배치를 기동할 수 없다 — 운영자 행위와 서버 호출의 경계")
    void serverKeyCannotTriggerBatch() {
        ResponseEntity<Map<String, Object>> res = serverPost("/admin/batch/settle/run",
                Map.of("settleDate", today.toString()));

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }
}
