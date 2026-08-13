package com.pointledger.concurrency;

import static org.assertj.core.api.Assertions.assertThat;

import com.pointledger.support.IntegrationTest;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/**
 * 동시성 불변식 검증 — 이 파일은 "재현 커밋"에서 실패하는 상태로 먼저 들어왔다.
 *
 * READ COMMITTED에서 redeem의 "읽고 → 검사하고 → 갱신"은 두 트랜잭션이 같은
 * 잔액 스냅샷을 읽으면 lost update가 된다: 잔액 5,000에서 3,000 사용 2건이
 * 동시에 통과해 원장에는 6,000이 차감됐는데 잔액은 2,000이 되는 식 —
 * 원장(진실)과 스냅샷(파생)이 어긋난다. 해결 커밋(지갑 행 비관적 락)이
 * 이 테스트를 통과시킨다. 격리수준 자체의 거동은 IsolationLevelObservationTest 참조.
 */
class RedeemConcurrencyTest extends IntegrationTest {

    private static final int THREADS = 20;

    /**
     * [실험 브랜치 주의] main(비관적 락)에서는 "정확히 5건 성공"을 단언한다.
     * 낙관적 락에서는 재시도 소진(CONFLICT_RETRY_EXHAUSTED)이 정상 결과라
     * 성공 수가 5 이하로 내려갈 수 있다 — 잔액이 남았는데도 실패하는 요청이
     * 생긴다는 것, 이것이 경합 구간에서 낙관적 전략이 치르는 비용이다.
     * 대신 정합성 불변식(원장 SUM == 잔액, 초과 지출 0)은 그대로 단언한다.
     */
    @Test
    @DisplayName("잔액 5,000에서 1,000 사용 20건 동시 진입 — 초과 지출 없이 최대 5건 성공")
    void concurrentRedeemMustNotOverspend() throws InterruptedException {
        serverPost("/wallets", Map.of("userId", 42));
        serverPost("/points/earn", Map.of("userId", 42, "amount", 5000,
                "refType", "ORDER", "refId", "seed", "expireDays", 30));

        AtomicInteger succeeded = new AtomicInteger();
        AtomicInteger retryExhausted = new AtomicInteger();
        Queue<Object> unexpected = new ConcurrentLinkedQueue<>(); // 스레드 안 단언은 삼켜지므로 수집 후 검증
        runConcurrently(i -> {
            ResponseEntity<Map<String, Object>> res = serverPost("/points/redeem",
                    Map.of("userId", 42, "amount", 1000, "refType", "ORDER", "refId", "ord-" + i));
            Object code = res.getBody() == null ? null : res.getBody().get("code");
            if (res.getStatusCode() == HttpStatus.OK) {
                succeeded.incrementAndGet();
            } else if ("CONFLICT_RETRY_EXHAUSTED".equals(code)) {
                retryExhausted.incrementAndGet();
            } else if (!"INSUFFICIENT_BALANCE".equals(code)) {
                unexpected.add(res.getBody());
            }
        });

        assertThat(unexpected).isEmpty();
        assertThat(succeeded.get()).isBetween(1, 5);
        long expected = 5000L - succeeded.get() * 1000L;
        assertThat(walletBalance()).isEqualTo(expected);
        assertThat(ledgerDerivedBalance()).isEqualTo(expected); // 원장(진실)과 스냅샷의 일치
        System.out.printf("[optimistic-retry] redeem 성공 %d건 / 재시도 소진 %d건%n",
                succeeded.get(), retryExhausted.get());
    }

    /**
     * [실험 브랜치 주의] main(비관적 락)에서는 20건 전부 성공을 단언한다.
     * 낙관적 락에서는 "그냥 줄을 서면 되는" 적립조차 버전 충돌로 재시도를 소모하고,
     * 소진되면 실패한다 — 증가 연산의 실패는 사용자 입장에서 이해받기 어려운 비용.
     * 성공한 건수만큼 정확히 반영됐는지(소실 0건)를 단언한다.
     */
    @Test
    @DisplayName("동시 적립 20건 — 성공한 건수만큼 정확히 반영된다 (lost update 0)")
    void concurrentEarnMustNotLoseUpdates() throws InterruptedException {
        serverPost("/wallets", Map.of("userId", 42));

        AtomicInteger succeeded = new AtomicInteger();
        Queue<Object> unexpected = new ConcurrentLinkedQueue<>();
        runConcurrently(i -> {
            ResponseEntity<Map<String, Object>> res = serverPost("/points/earn",
                    Map.of("userId", 42, "amount", 1000,
                            "refType", "PROMO", "refId", "promo-" + i, "expireDays", 30));
            Object code = res.getBody() == null ? null : res.getBody().get("code");
            if (res.getStatusCode() == HttpStatus.CREATED) {
                succeeded.incrementAndGet();
            } else if (!"CONFLICT_RETRY_EXHAUSTED".equals(code)) {
                unexpected.add(res.getBody());
            }
        });

        assertThat(unexpected).isEmpty();
        assertThat(succeeded.get()).isPositive();
        // 성공으로 응답한 것은 전부 반영, 실패로 응답한 것은 전혀 반영되지 않아야 한다
        assertThat(walletBalance()).isEqualTo(succeeded.get() * 1000L);
        assertThat(ledgerDerivedBalance()).isEqualTo(succeeded.get() * 1000L);
        System.out.printf("[optimistic-retry] earn 성공 %d/%d건%n", succeeded.get(), THREADS);
    }

    /** THREADS개의 작업을 latch로 동시 출발시키고 완료까지 대기 */
    private void runConcurrently(java.util.function.IntConsumer task) throws InterruptedException {
        ExecutorService pool = Executors.newFixedThreadPool(THREADS);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(THREADS);
        for (int i = 0; i < THREADS; i++) {
            int idx = i;
            pool.submit(() -> {
                try {
                    start.await();
                    task.accept(idx);
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
    }

    private long walletBalance() {
        return jdbc.queryForObject("SELECT balance FROM wallets WHERE user_id = 42", Long.class);
    }

    /** 원장에서 파생한 잔액 — wallets.balance와 항상 같아야 하는 값 */
    private long ledgerDerivedBalance() {
        return jdbc.queryForObject("""
                SELECT COALESCE(SUM(CASE WHEN type IN ('EARN','CANCEL','ADMIN_GRANT')
                                         THEN amount ELSE -amount END), 0)
                FROM ledger_entries
                """, Long.class);
    }
}
