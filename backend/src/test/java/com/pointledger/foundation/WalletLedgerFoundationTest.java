package com.pointledger.foundation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.pointledger.support.IntegrationTest;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/**
 * Phase 1 계약 검증 — 지갑 생성/조회, 적립/사용 happy path, 원장 커서 조회,
 * 인증 경계, append-only 트리거. 동시성·멱등성은 아직 대상이 아니다(Phase 2·3).
 */
class WalletLedgerFoundationTest extends IntegrationTest {

    private static final Map<String, Object> EARN_5000 = Map.of(
            "userId", 42, "amount", 5000, "refType", "ORDER", "refId", "ord-1", "expireDays", 10);

    @Nested
    @DisplayName("지갑")
    class WalletTests {

        @Test
        @DisplayName("생성 후 잔액 0으로 조회된다")
        void createAndRead() {
            ResponseEntity<Map<String, Object>> created =
                    serverPost("/wallets", Map.of("userId", 42));
            assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);

            ResponseEntity<Map<String, Object>> balance = serverGet("/wallets/42/balance");
            assertThat(balance.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(balance.getBody()).containsEntry("balance", 0)
                    .containsEntry("expiringSoon", 0);
        }

        @Test
        @DisplayName("같은 사용자 지갑을 두 번 만들면 409")
        void duplicate() {
            serverPost("/wallets", Map.of("userId", 42));
            ResponseEntity<Map<String, Object>> dup = serverPost("/wallets", Map.of("userId", 42));
            assertThat(dup.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
            assertThat(dup.getBody()).containsEntry("code", "WALLET_ALREADY_EXISTS");
        }

        @Test
        @DisplayName("없는 지갑 조회는 404")
        void notFound() {
            ResponseEntity<Map<String, Object>> res = serverGet("/wallets/999/balance");
            assertThat(res.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
            assertThat(res.getBody()).containsEntry("code", "WALLET_NOT_FOUND");
        }
    }

    @Nested
    @DisplayName("적립/사용 happy path")
    class EarnRedeemTests {

        @Test
        @DisplayName("적립하면 원장 EARN·로트·잔액이 한 번에 생긴다")
        void earn() {
            serverPost("/wallets", Map.of("userId", 42));
            ResponseEntity<Map<String, Object>> res = serverPost("/points/earn", EARN_5000);

            assertThat(res.getStatusCode()).isEqualTo(HttpStatus.CREATED);
            assertThat(res.getBody()).containsEntry("balanceAfter", 5000);
            assertThat(res.getBody()).containsKeys("entryId", "lotId", "expiresAt");

            // 원장·로트·잔액 3자 일치 — "잔액은 원장의 파생값" 불변식의 최소 확인
            assertThat(jdbc.queryForObject(
                    "SELECT balance FROM wallets WHERE user_id = 42", Long.class)).isEqualTo(5000);
            assertThat(jdbc.queryForObject(
                    "SELECT count(*) FROM ledger_entries WHERE type = 'EARN'", Long.class)).isEqualTo(1);
            assertThat(jdbc.queryForObject(
                    "SELECT remaining FROM point_lots", Long.class)).isEqualTo(5000);
        }

        @Test
        @DisplayName("30일 내 만료 예정액만 expiringSoon으로 집계된다")
        void expiringSoon() {
            serverPost("/wallets", Map.of("userId", 42));
            serverPost("/points/earn", EARN_5000); // 10일 뒤 만료 — 집계 대상
            serverPost("/points/earn", Map.of("userId", 42, "amount", 3000,
                    "refType", "PROMO", "refId", "promo-1", "expireDays", 100)); // 대상 아님

            ResponseEntity<Map<String, Object>> balance = serverGet("/wallets/42/balance");
            assertThat(balance.getBody()).containsEntry("balance", 8000)
                    .containsEntry("expiringSoon", 5000);
        }

        @Test
        @DisplayName("사용하면 원장 REDEEM과 잔액 차감이 함께 기록된다")
        void redeem() {
            serverPost("/wallets", Map.of("userId", 42));
            serverPost("/points/earn", EARN_5000);

            ResponseEntity<Map<String, Object>> res = serverPost("/points/redeem",
                    Map.of("userId", 42, "amount", 3000, "refType", "ORDER", "refId", "ord-2"));

            assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(res.getBody()).containsEntry("balanceAfter", 2000);
            assertThat(jdbc.queryForObject(
                    "SELECT balance_after FROM ledger_entries WHERE type = 'REDEEM'",
                    Long.class)).isEqualTo(2000);
        }

        @Test
        @DisplayName("잔액 부족이면 409 + 부족액을 알려준다")
        void insufficient() {
            serverPost("/wallets", Map.of("userId", 42));
            serverPost("/points/earn", EARN_5000);

            ResponseEntity<Map<String, Object>> res = serverPost("/points/redeem",
                    Map.of("userId", 42, "amount", 8000, "refType", "ORDER", "refId", "ord-3"));

            assertThat(res.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
            assertThat(res.getBody()).containsEntry("code", "INSUFFICIENT_BALANCE");
            @SuppressWarnings("unchecked")
            Map<String, Object> details = (Map<String, Object>) res.getBody().get("details");
            assertThat(details).containsEntry("shortage", 3000);
            // 실패한 사용은 원장에 아무것도 남기지 않는다
            assertThat(jdbc.queryForObject(
                    "SELECT count(*) FROM ledger_entries WHERE type = 'REDEEM'", Long.class)).isZero();
        }
    }

    @Nested
    @DisplayName("원장 커서 조회")
    class LedgerPageTests {

        @Test
        @DisplayName("id 내림차순으로 끊어 읽고 마지막 페이지는 nextCursor가 없다")
        void cursorPagination() {
            serverPost("/wallets", Map.of("userId", 42));
            for (int i = 1; i <= 5; i++) {
                serverPost("/points/earn", Map.of("userId", 42, "amount", 1000 * i,
                        "refType", "ORDER", "refId", "ord-" + i, "expireDays", 30));
            }

            ResponseEntity<Map<String, Object>> page1 = serverGet("/wallets/42/ledger?size=2");
            List<Map<String, Object>> items1 = items(page1);
            assertThat(items1).hasSize(2);
            assertThat(items1.get(0).get("amount")).isEqualTo(5000); // 최신부터
            Integer cursor = (Integer) page1.getBody().get("nextCursor");
            assertThat(cursor).isNotNull();

            ResponseEntity<Map<String, Object>> page2 =
                    serverGet("/wallets/42/ledger?size=2&cursor=" + cursor);
            assertThat(items(page2)).hasSize(2);

            Integer cursor2 = (Integer) page2.getBody().get("nextCursor");
            ResponseEntity<Map<String, Object>> page3 =
                    serverGet("/wallets/42/ledger?size=2&cursor=" + cursor2);
            assertThat(items(page3)).hasSize(1);
            assertThat(page3.getBody().get("nextCursor")).isNull();
        }

        @SuppressWarnings("unchecked")
        private List<Map<String, Object>> items(ResponseEntity<Map<String, Object>> res) {
            return (List<Map<String, Object>>) res.getBody().get("items");
        }
    }

    @Nested
    @DisplayName("인증 경계")
    class AuthBoundaryTests {

        @Test
        @DisplayName("API 키 없이 서버 API를 호출하면 401")
        void noKey() {
            ResponseEntity<Map<String, Object>> res =
                    anonymousPost("/wallets", Map.of("userId", 1));
            assertThat(res.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
            assertThat(res.getBody()).containsEntry("code", "UNAUTHORIZED");
        }

        @Test
        @DisplayName("운영자 토큰으로 서버 간 API를 호출하면 403 — 감사 주체 분리")
        void adminTokenOnServerApi() {
            String token = adminLogin();
            ResponseEntity<Map<String, Object>> res =
                    adminPost("/wallets", Map.of("userId", 1), token);
            assertThat(res.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        }

        @Test
        @DisplayName("API 키로 운영자 API를 호출하면 403")
        void serverKeyOnAdminApi() {
            ResponseEntity<Map<String, Object>> res =
                    serverPost("/admin/api-keys", Map.of("name", "hack"));
            assertThat(res.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        }

        @Test
        @DisplayName("틀린 비밀번호는 401, 올바른 로그인 후 API 키 발급은 201")
        void loginAndIssueKey() {
            ResponseEntity<Map<String, Object>> bad = anonymousPost("/auth/login",
                    Map.of("email", ADMIN_EMAIL, "password", "wrong-password"));
            assertThat(bad.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);

            String token = adminLogin();
            ResponseEntity<Map<String, Object>> created =
                    adminPost("/admin/api-keys", Map.of("name", "promo-server"), token);
            assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
            assertThat((String) created.getBody().get("apiKey")).startsWith("plk_");
            // 저장된 것은 해시뿐 — 원문 유출 방지 확인
            assertThat(jdbc.queryForObject(
                    "SELECT key_hash FROM api_keys WHERE name = 'promo-server'", String.class))
                    .hasSize(64).isNotEqualTo(created.getBody().get("apiKey"));
        }
    }

    @Nested
    @DisplayName("append-only 원장")
    class AppendOnlyTests {

        @Test
        @DisplayName("원장 UPDATE/DELETE는 DB 트리거가 거부한다")
        void ledgerIsImmutable() {
            serverPost("/wallets", Map.of("userId", 42));
            serverPost("/points/earn", EARN_5000);

            assertThatThrownBy(() ->
                    jdbc.update("UPDATE ledger_entries SET amount = 1 WHERE type = 'EARN'"))
                    .hasMessageContaining("append-only");
            assertThatThrownBy(() -> jdbc.update("DELETE FROM ledger_entries"))
                    .hasMessageContaining("append-only");
        }
    }
}
