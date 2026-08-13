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

    @Test
    @DisplayName("잔액 5,000에서 1,000 사용 20건 동시 진입 — 정확히 5건만 성공해야 한다")
    void concurrentRedeemMustNotOverspend() throws InterruptedException {
        serverPost("/wallets", Map.of("userId", 42));
        serverPost("/points/earn", Map.of("userId", 42, "amount", 5000,
                "refType", "ORDER", "refId", "seed", "expireDays", 30));

        AtomicInteger succeeded = new AtomicInteger();
        Queue<Object> unexpected = new ConcurrentLinkedQueue<>(); // 스레드 안 단언은 삼켜지므로 수집 후 검증
        runConcurrently(i -> {
            ResponseEntity<Map<String, Object>> res = serverPost("/points/redeem",
                    Map.of("userId", 42, "amount", 1000, "refType", "ORDER", "refId", "ord-" + i));
            if (res.getStatusCode() == HttpStatus.OK) {
                succeeded.incrementAndGet();
            } else if (!"INSUFFICIENT_BALANCE".equals(res.getBody().get("code"))) {
                // 실패는 반드시 "잔액 부족"이어야 한다 — 다른 에러로 새는 것은 별개의 버그
                unexpected.add(res.getBody());
            }
        });

        assertThat(unexpected).isEmpty();
        assertThat(succeeded.get()).isEqualTo(5);
        assertThat(walletBalance()).isZero();
        assertThat(ledgerDerivedBalance()).isZero(); // 원장(진실)과 스냅샷의 일치
    }

    @Test
    @DisplayName("동시 적립 10건 — 전부 반영되어야 한다 (적립도 절대값 UPDATE라 lost update 대상)")
    void concurrentEarnMustNotLoseUpdates() throws InterruptedException {
        serverPost("/wallets", Map.of("userId", 42));

        Queue<Object> failed = new ConcurrentLinkedQueue<>();
        runConcurrently(i -> {
            ResponseEntity<Map<String, Object>> res = serverPost("/points/earn",
                    Map.of("userId", 42, "amount", 1000,
                            "refType", "PROMO", "refId", "promo-" + i, "expireDays", 30));
            if (res.getStatusCode() != HttpStatus.CREATED) {
                failed.add(res.getBody());
            }
        });
        assertThat(failed).isEmpty();

        // JPA dirty checking은 델타가 아니라 절대값을 쓴다(SET balance = 읽은값+1000).
        // 직렬화가 없으면 20,000이 아니라 그보다 작은 값이 남는다.
        assertThat(walletBalance()).isEqualTo(THREADS * 1000L);
        assertThat(ledgerDerivedBalance()).isEqualTo(THREADS * 1000L);
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
