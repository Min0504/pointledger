package com.pointledger.batch;

import static org.assertj.core.api.Assertions.assertThat;

import com.pointledger.ledger.LedgerService;
import com.pointledger.support.IntegrationTest;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobParameters;
import org.springframework.batch.core.JobParametersBuilder;
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.item.ItemWriter;
import org.springframework.batch.test.JobLauncherTestUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.TestPropertySource;

/**
 * 배치 도중 프로세스 사망 → 이어서 재시작 (기획서 §10-1).
 *
 * 청크 2로 줄이고 두 번째 청크에 장애를 주입한다. 검증하는 문장:
 * "완료된 청크는 커밋으로 살아남고, 실패한 청크는 통째로 롤백되며,
 *  재시작은 실패 지점부터 이어가고, 어떤 로트도 두 번 만료되지 않는다."
 */
@TestPropertySource(properties = "pointledger.batch.expire.chunk-size=2")
class ExpireJobRestartTest extends IntegrationTest {

    @TestConfiguration
    static class PoisonedBatch {

        static final AtomicReference<Long> POISON_LOT_ID = new AtomicReference<>();

        /** 지정한 로트가 담긴 청크에서 한 번만 터진다 — 프로세스 사망 재연 */
        @Bean
        @Primary
        ItemWriter<Long> poisonedExpireWriter(LedgerService ledgerService) {
            return chunk -> {
                Long poison = POISON_LOT_ID.get();
                if (poison != null && chunk.getItems().contains(poison)) {
                    POISON_LOT_ID.set(null);
                    throw new IllegalStateException("주입된 장애 — 이 청크는 롤백되어야 한다");
                }
                ledgerService.expireLots(new ArrayList<>(chunk.getItems()));
            };
        }

        @Bean
        JobLauncherTestUtils expireJobTestUtils(
                JobLauncher launcher, JobRepository repository, Job expirePointsJob) {
            JobLauncherTestUtils utils = new JobLauncherTestUtils();
            utils.setJobLauncher(launcher);
            utils.setJobRepository(repository);
            utils.setJob(expirePointsJob);
            return utils;
        }
    }

    @Autowired
    private JobLauncherTestUtils jobUtils;

    @Test
    @DisplayName("두 번째 청크에서 죽어도 — 재시작하면 실패 지점부터, 이중 만료 없이 완주한다")
    void resumesFromFailedChunkWithoutDoubleExpiry() throws Exception {
        serverPost("/wallets", Map.of("userId", 1));
        long[] lots = new long[5];
        long total = 0;
        for (int i = 0; i < 5; i++) {
            long amount = (i + 1) * 100L; // 100..500, 합 1,500
            total += amount;
            lots[i] = ((Number) serverPost("/points/earn",
                    Map.of("userId", 1, "amount", amount, "refType", "ORDER",
                            "refId", "lot-" + i, "expireDays", 30))
                    .getBody().get("lotId")).longValue();
        }
        jdbc.update("UPDATE point_lots SET expires_at = now() - interval '1 day'");

        // 청크 2 → [l0,l1] [l2,l3] [l4]. 두 번째 청크(l2)에 장애 주입
        PoisonedBatch.POISON_LOT_ID.set(lots[2]);
        JobParameters params = new JobParametersBuilder()
                .addString("asOf", LocalDate.now().toString())
                .toJobParameters();

        JobExecution first = jobUtils.launchJob(params);

        assertThat(first.getStatus()).isEqualTo(BatchStatus.FAILED);
        // 첫 청크(l0, l1)만 커밋됐다 — 실패 청크는 흔적이 없다
        assertThat(expiredCount()).isEqualTo(2);
        assertThat(query("SELECT count(*) FROM point_lots WHERE status = 'EXPIRED'")).isEqualTo(2);
        assertThat(query("SELECT balance FROM wallets WHERE user_id = 1"))
                .isEqualTo(total - 100 - 200);

        // 같은 파라미터로 재기동 = 같은 JobInstance의 재시작 — 이어서 완주
        JobExecution second = jobUtils.launchJob(params);

        assertThat(second.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(expiredCount()).isEqualTo(5);
        // 로트당 EXPIRE 원장은 정확히 한 줄 — 재시작이 이중 만료를 만들지 않았다
        assertThat(query("SELECT count(DISTINCT ref_id) FROM ledger_entries WHERE type = 'EXPIRE'"))
                .isEqualTo(5);
        assertThat(query("SELECT balance FROM wallets WHERE user_id = 1")).isZero();
        assertThat(query("SELECT COALESCE(SUM(remaining), 0) FROM point_lots")).isZero();
    }

    private long expiredCount() {
        return query("SELECT count(*) FROM ledger_entries WHERE type = 'EXPIRE'");
    }

    private long query(String sql) {
        return jdbc.queryForObject(sql, Long.class);
    }
}
