package com.pointledger.idempotency;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.pointledger.support.IntegrationTest;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/**
 * 멱등성 계약 검증 (기획서 문제 2 — Stripe/Toss Payments 패턴).
 *
 * 재현 커밋에서 실패하던 시나리오(타임아웃 재시도 → 이중 적립)가 이제 통과한다.
 * 핵심 계약: 같은 Idempotency-Key는 언제나 같은 응답을 받고, 부수효과는 정확히
 * 한 번만 일어난다. 키가 요청의 정체성이다 — 서버는 바디가 같아도 키가 다르면
 * 다른 요청으로, 키가 같은데 바디가 다르면 호출자 버그(422)로 판정한다.
 */
class IdempotencyIntegrationTest extends IntegrationTest {

    private static final Map<String, Object> EARN = Map.of("userId", 7, "amount", 1000,
            "refType", "ORDER", "refId", "ord-1", "expireDays", 30);

    @BeforeEach
    void createWallet() {
        serverPost("/wallets", Map.of("userId", 7));
    }

    @Test
    @DisplayName("타임아웃 재시도 — 같은 키의 재시도는 저장된 응답을 재생하고 원장에는 한 줄만 남는다")
    void retriedEarnMustNotDoubleCredit() {
        ResponseEntity<Map<String, Object>> first = serverPost("/points/earn", EARN, "earn-ord-1");
        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.CREATED);

        // 응답 유실을 겪은 호출자의 재시도 — 같은 키, 같은 바디
        ResponseEntity<Map<String, Object>> retry = serverPost("/points/earn", EARN, "earn-ord-1");

        assertThat(retry.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(retry.getBody()).isEqualTo(first.getBody()); // entryId까지 동일한 재생
        assertThat(earnEntryCount()).isEqualTo(1);
        assertThat(walletBalance()).isEqualTo(1000);
    }

    @Test
    @DisplayName("같은 바디라도 키가 다르면 다른 요청 — 각각 기장된다")
    void differentKeysAreDifferentRequests() {
        serverPost("/points/earn", EARN, "key-a");
        serverPost("/points/earn", EARN, "key-b");

        // 키 계약 없이는 "재시도"와 "같은 내용의 새 요청"을 구분할 방법이 없다 —
        // 재현 테스트가 실패했던 근본 원인이자, 중복 판정을 키로 옮긴 이유.
        assertThat(earnEntryCount()).isEqualTo(2);
        assertThat(walletBalance()).isEqualTo(2000);
    }

    @Test
    @DisplayName("같은 키 + 다른 바디 = 키 재사용 실수 — 422로 조기에 드러낸다")
    void keyReuseWithDifferentBodyIs422() {
        serverPost("/points/earn", EARN, "reused-key");

        Map<String, Object> different = Map.of("userId", 7, "amount", 9999,
                "refType", "ORDER", "refId", "ord-2", "expireDays", 30);
        ResponseEntity<Map<String, Object>> res =
                serverPost("/points/earn", different, "reused-key");

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(res.getBody()).containsEntry("code", "IDEMPOTENCY_KEY_REUSED");
        assertThat(walletBalance()).isEqualTo(1000); // 두 번째 요청은 실행되지 않았다
    }

    @Test
    @DisplayName("Idempotency-Key 없는 변경 요청은 400 — 계약을 선택이 아닌 필수로 강제한다")
    void missingHeaderIs400() {
        ResponseEntity<Map<String, Object>> res =
                exchange(HttpMethod.POST, "/points/earn", EARN, apiKeyHeaders());

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(res.getBody()).containsEntry("code", "VALIDATION_FAILED");
        assertThat(earnEntryCount()).isZero();
    }

    @Test
    @DisplayName("결정적 실패(잔액 부족)도 그 키의 확정 결과 — 잔액이 생겨도 같은 답을 재생한다")
    void domainErrorIsCachedAndReplayed() {
        Map<String, Object> redeem = Map.of("userId", 7, "amount", 500,
                "refType", "ORDER", "refId", "ord-9");

        // 잔액 0에서 사용 시도 → 409 잔액 부족이 이 키의 결과로 저장된다
        ResponseEntity<Map<String, Object>> first =
                serverPost("/points/redeem", redeem, "redeem-ord-9");
        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);

        serverPost("/points/earn", EARN); // 이후 잔액 1,000이 생겨도

        // 같은 키의 재시도는 여전히 저장된 409를 재생한다 — 시점에 따라 답이
        // 달라지면 호출자는 "재시도했더니 성공"이라는 비결정성을 떠안게 된다
        ResponseEntity<Map<String, Object>> retry =
                serverPost("/points/redeem", redeem, "redeem-ord-9");
        assertThat(retry.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(retry.getBody()).containsEntry("code", "INSUFFICIENT_BALANCE");

        assertThat(redeemEntryCount()).isZero();
        assertThat(walletBalance()).isEqualTo(1000); // 재생은 부수효과가 없다
    }

    @Test
    @DisplayName("동시 중복 10건 — 부수효과는 정확히 한 번, 나머지는 재생 또는 409 처리 중")
    void concurrentSameKeyExecutesExactlyOnce() throws InterruptedException {
        int threads = 10;
        Queue<Object> unexpected = new ConcurrentLinkedQueue<>();
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        for (int i = 0; i < threads; i++) {
            pool.submit(() -> {
                try {
                    start.await();
                    ResponseEntity<Map<String, Object>> res =
                            serverPost("/points/earn", EARN, "same-key");
                    // 허용되는 응답은 둘뿐: 201(실행 또는 재생) / 409(처리 중 — 재시도 유도)
                    boolean ok = res.getStatusCode() == HttpStatus.CREATED
                            || (res.getStatusCode() == HttpStatus.CONFLICT
                                && "IDEMPOTENT_IN_PROGRESS".equals(res.getBody().get("code")));
                    if (!ok) {
                        unexpected.add(res.getBody());
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
        }
        start.countDown();
        assertThat(done.await(30, TimeUnit.SECONDS)).isTrue();
        pool.shutdown();

        assertThat(unexpected).isEmpty();
        assertThat(earnEntryCount()).isEqualTo(1);
        assertThat(walletBalance()).isEqualTo(1000);
    }

    @Test
    @DisplayName("2차 방어 — 요청 상태 테이블을 우회한 직접 기장도 원장 유니크 인덱스가 거부한다")
    void ledgerUniqueIndexIsSecondDefense() {
        serverPost("/points/earn", EARN, "dup-key");

        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO ledger_entries
                    (wallet_id, type, amount, balance_after, idempotency_key, created_by)
                VALUES (1, 'EARN', 1000, 2000, 'dup-key', 'bypass')
                """))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("uq_ledger_idempotency_key");
    }

    private long earnEntryCount() {
        return jdbc.queryForObject(
                "SELECT count(*) FROM ledger_entries WHERE type = 'EARN'", Long.class);
    }

    private long redeemEntryCount() {
        return jdbc.queryForObject(
                "SELECT count(*) FROM ledger_entries WHERE type = 'REDEEM'", Long.class);
    }

    private long walletBalance() {
        return jdbc.queryForObject("SELECT balance FROM wallets WHERE user_id = 7", Long.class);
    }
}
