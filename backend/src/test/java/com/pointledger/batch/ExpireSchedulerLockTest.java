package com.pointledger.batch;

import static org.assertj.core.api.Assertions.assertThat;

import com.pointledger.support.IntegrationTest;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * 배치 이중 기동 차단 (기획서 §10-2) — 서버 2대가 같은 시각에 크론을 울려도
 * ShedLock이 한 대만 통과시킨다. lockAtLeastFor 동안은 완주 후에도 잠겨 있어
 * 시계가 조금 어긋난 인스턴스의 뒷북 기동도 막는다.
 */
class ExpireSchedulerLockTest extends IntegrationTest {

    @Autowired
    private ExpireBatchScheduler scheduler;

    @Test
    @DisplayName("동시에 두 번 울린 크론 — Job은 한 번만 뜨고 락 흔적이 남는다")
    void concurrentTriggersLaunchExactlyOnce() throws Exception {
        serverPost("/wallets", Map.of("userId", 1));
        serverPost("/points/earn", Map.of("userId", 1, "amount", 1000,
                "refType", "ORDER", "refId", "aged", "expireDays", 30));
        jdbc.update("UPDATE point_lots SET expires_at = now() - interval '1 day'");

        int triggers = 2;
        ExecutorService pool = Executors.newFixedThreadPool(triggers);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(triggers);
        for (int i = 0; i < triggers; i++) {
            pool.submit(() -> {
                try {
                    start.await();
                    scheduler.runDailyExpiry(); // @SchedulerLock 프록시 경유
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

        // 실행은 정확히 한 번 — 인스턴스도, 소멸 원장도 하나뿐
        assertThat(query("SELECT count(*) FROM batch_job_instance")).isEqualTo(1);
        assertThat(query("SELECT count(*) FROM ledger_entries WHERE type = 'EXPIRE'")).isEqualTo(1);
        assertThat(query("SELECT balance FROM wallets WHERE user_id = 1")).isZero();
        // 락 흔적 — 이름은 Job과 같고, lockAtLeastFor 동안 유지된다
        assertThat(query("SELECT count(*) FROM shedlock WHERE name = 'expirePointsJob'"))
                .isEqualTo(1);
    }

    private long query(String sql) {
        return jdbc.queryForObject(sql, Long.class);
    }
}
