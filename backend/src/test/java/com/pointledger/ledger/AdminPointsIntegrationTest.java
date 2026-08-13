package com.pointledger.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.pointledger.support.IntegrationTest;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/**
 * 운영자 수동 지급/회수 — 감사 3요소(누가·언제·왜)가 강제되는지 (기획서 §2).
 * "사유 없는 수동 조작은 기록될 수 없다"는 검증 계층(400)과 스키마 CHECK(최후 방어)
 * 두 겹으로 지킨다.
 */
class AdminPointsIntegrationTest extends IntegrationTest {

    private String token;

    @BeforeEach
    void setUp() {
        serverPost("/wallets", Map.of("userId", 42));
        token = adminLogin();
    }

    @Test
    @DisplayName("수동 지급 — 로트가 생기고 원장에 운영자 이메일과 사유가 남는다")
    void grantLeavesAuditTrail() {
        ResponseEntity<Map<String, Object>> res = adminPost("/admin/points/grant",
                Map.of("userId", 42, "amount", 3000, "reason", "CS 보상 — 배송 지연", "expireDays", 30),
                token);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(res.getBody()).containsEntry("balanceAfter", 3000).containsKey("lotId");

        Map<String, Object> entry = jdbc.queryForMap(
                "SELECT type, reason, created_by FROM ledger_entries");
        assertThat(entry).containsEntry("type", "ADMIN_GRANT")
                .containsEntry("reason", "CS 보상 — 배송 지연")
                .containsEntry("created_by", ADMIN_EMAIL); // API 키 이름이 아니라 운영자 식별자
    }

    @Test
    @DisplayName("사유 없는 수동 조작은 400 — 스키마 CHECK가 최후 방어로 겹친다")
    void reasonIsMandatory() {
        ResponseEntity<Map<String, Object>> res = adminPost("/admin/points/grant",
                Map.of("userId", 42, "amount", 3000, "expireDays", 30), token);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);

        // 검증 계층을 우회해도 DB가 거부한다
        jdbc.update("INSERT INTO wallets (user_id, balance) VALUES (7, 0)");
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO ledger_entries (wallet_id, type, amount, balance_after, created_by)
                VALUES ((SELECT id FROM wallets WHERE user_id = 7), 'ADMIN_GRANT', 100, 100, 'rogue')
                """)).hasMessageContaining("violates check constraint");
    }

    @Test
    @DisplayName("수동 회수 — 사용과 같은 FIFO 차감 경로를 지나 로트 정합성이 유지된다")
    void revokeConsumesLotsLikeRedeem() {
        adminPost("/admin/points/grant",
                Map.of("userId", 42, "amount", 3000, "reason", "이벤트 지급", "expireDays", 30), token);

        ResponseEntity<Map<String, Object>> res = adminPost("/admin/points/revoke",
                Map.of("userId", 42, "amount", 1000, "reason", "어뷰징 회수"), token);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(res.getBody()).containsEntry("balanceAfter", 2000);
        assertThat((java.util.List<?>) res.getBody().get("consumedLots")).hasSize(1);
        assertThat(jdbc.queryForObject(
                "SELECT SUM(remaining) FROM point_lots", Long.class)).isEqualTo(2000);

        ResponseEntity<Map<String, Object>> tooMuch = adminPost("/admin/points/revoke",
                Map.of("userId", 42, "amount", 99999, "reason", "회수"), token);
        assertThat(tooMuch.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(tooMuch.getBody()).containsEntry("code", "INSUFFICIENT_BALANCE");
    }

    @Test
    @DisplayName("서버 API 키로는 수동 지급을 호출할 수 없다 — 감사 주체 분리")
    void serverKeyCannotGrant() {
        ResponseEntity<Map<String, Object>> res = serverPost("/admin/points/grant",
                Map.of("userId", 42, "amount", 3000, "reason", "탈취 시도", "expireDays", 30));
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }
}
