package com.pointledger.admin;

import static org.assertj.core.api.Assertions.assertThat;

import com.pointledger.support.IntegrationTest;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/**
 * 어드민 콘솔이 쓰는 읽기 API — 대시보드 집계, 배치 실행 이력, 그리고
 * "조회는 겸용, 쓰기는 분리"라는 인가 경계가 검증 대상이다.
 */
class AdminConsoleApiTest extends IntegrationTest {

    private String adminToken;

    @BeforeEach
    void setUpFixtures() {
        adminToken = adminLogin();
        serverPost("/wallets", Map.of("userId", 42));
    }

    @Test
    @DisplayName("대시보드 — 오늘의 유형별 집계와 유통 잔액, 처리 대기 신호를 한 번에 준다")
    void dashboardAggregatesToday() {
        serverPost("/points/earn", Map.of("userId", 42, "amount", 5000,
                "refType", "ORDER", "refId", "e-1", "expireDays", 365));
        serverPost("/points/redeem", Map.of("userId", 42, "amount", 2000,
                "refType", "ORDER", "refId", "u-1"));

        ResponseEntity<Map<String, Object>> res = adminGet("/admin/dashboard", adminToken);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(res.getBody().get("date"))
                .isEqualTo(LocalDate.now(ZoneId.of("Asia/Seoul")).toString());
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> byType = (List<Map<String, Object>>) res.getBody().get("byType");
        assertThat(byType).containsExactlyInAnyOrder(
                Map.of("type", "EARN", "count", 1, "amount", 5000),
                Map.of("type", "REDEEM", "count", 1, "amount", 2000));
        assertThat(res.getBody().get("circulating")).isEqualTo(3000);
        assertThat(res.getBody().get("wallets")).isEqualTo(1);
        assertThat(res.getBody().get("unresolvedIssues")).isEqualTo(0);
    }

    @Test
    @DisplayName("배치 실행 이력 — 수동 기동이 JobRepository 메타데이터에서 파라미터와 함께 조회된다")
    void listsBatchExecutions() {
        String date = LocalDate.now(ZoneId.of("Asia/Seoul")).toString();
        adminPost("/admin/batch/settle/run", Map.of("settleDate", date), adminToken);

        ResponseEntity<Map<String, Object>[]> res = rest.exchange(
                "/admin/batch/executions", org.springframework.http.HttpMethod.GET,
                new org.springframework.http.HttpEntity<>(null, bearerHeaders(adminToken)),
                (Class<Map<String, Object>[]>) (Class<?>) Map[].class);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(res.getBody()).isNotEmpty();
        Map<String, Object> latest = res.getBody()[0];
        assertThat(latest.get("jobName")).isEqualTo("settleDailyJob");
        assertThat(latest.get("status")).isEqualTo("COMPLETED");
        @SuppressWarnings("unchecked")
        Map<String, String> params = (Map<String, String>) latest.get("parameters");
        assertThat(params.get("settleDate")).isEqualTo(date);
    }

    @Test
    @DisplayName("조회는 겸용, 쓰기는 분리 — 운영자 토큰으로 지갑 조회는 되고 생성·적립은 안 된다")
    void adminCanReadWalletsButNotWrite() {
        ResponseEntity<Map<String, Object>> balance =
                adminGet("/wallets/42/balance", adminToken);
        ResponseEntity<Map<String, Object>> ledger =
                adminGet("/wallets/42/ledger?size=10", adminToken);
        ResponseEntity<Map<String, Object>> createWallet =
                adminPost("/wallets", Map.of("userId", 43), adminToken);
        ResponseEntity<Map<String, Object>> earn = adminPost("/points/earn",
                Map.of("userId", 42, "amount", 1000, "refType", "ORDER",
                        "refId", "e-x", "expireDays", 30), adminToken);

        assertThat(balance.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(ledger.getStatusCode()).isEqualTo(HttpStatus.OK);
        // 쓰기는 서버 간 API 키 전용 — 원장의 created_by에 운영자 이메일과
        // 서버 키 이름이 섞이면 "누가 조작했나"에 답할 수 없다
        assertThat(createWallet.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(earn.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    private org.springframework.http.HttpHeaders bearerHeaders(String token) {
        org.springframework.http.HttpHeaders headers = new org.springframework.http.HttpHeaders();
        headers.setBearerAuth(token);
        return headers;
    }
}
