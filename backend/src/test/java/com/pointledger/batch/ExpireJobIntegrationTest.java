package com.pointledger.batch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.pointledger.support.IntegrationTest;
import java.time.LocalDate;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobParametersBuilder;
import org.springframework.batch.core.StepExecution;
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.batch.core.repository.JobInstanceAlreadyCompleteException;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.test.JobLauncherTestUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

/**
 * 만료 Job 통합 검증 (기획서 문제 4, §11 배치 레벨).
 *
 * 검증 목표: ① 만료 대상만 정확히 소멸(부분 소진·미만료 로트 보호)
 * ② 온라인 사용과 같은 락 규약으로 잔액·원장·로트 정합성 유지
 * ③ 재수행 멱등성 — JobInstance 거부(1차)와 데이터 조건(2차)의 계층 방어.
 */
class ExpireJobIntegrationTest extends IntegrationTest {

    @TestConfiguration
    static class BatchTestUtils {
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

    private final String asOf = LocalDate.now().toString();

    private long earn(long userId, long amount, String refId) {
        return ((Number) serverPost("/points/earn",
                Map.of("userId", userId, "amount", amount, "refType", "ORDER",
                        "refId", refId, "expireDays", 30))
                .getBody().get("lotId")).longValue();
    }

    private void age(long lotId, String interval) {
        jdbc.update("UPDATE point_lots SET expires_at = now() - interval '" + interval
                + "' WHERE id = ?", lotId);
    }

    private JobExecution launch(Long attempt) throws Exception {
        JobParametersBuilder params = new JobParametersBuilder().addString("asOf", asOf);
        if (attempt != null) {
            params.addLong("attempt", attempt);
        }
        return jobUtils.launchJob(params.toJobParameters());
    }

    @Test
    @DisplayName("만료 지난 로트의 '지금 남은 만큼'만 소멸한다 — 부분 소진·미만료 로트 보호")
    void expiresOnlyDueRemainings() throws Exception {
        serverPost("/wallets", Map.of("userId", 1));
        serverPost("/wallets", Map.of("userId", 2));

        long agedPartial = earn(1, 2000, "w1-aged");   // 만료 예정 + 일부 사용
        age(agedPartial, "2 days");
        serverPost("/points/redeem", Map.of("userId", 1, "amount", 500,
                "refType", "ORDER", "refId", "w1-use")); // FIFO가 만료 임박분부터 500 소진
        long alive = earn(1, 3000, "w1-alive");         // 만료 전 — 건드리면 안 된다
        long agedFull = earn(2, 700, "w2-aged");        // 다른 지갑의 만료 대상
        age(agedFull, "1 day");

        JobExecution execution = launch(null);

        assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        StepExecution step = execution.getStepExecutions().iterator().next();
        assertThat(step.getReadCount()).isEqualTo(2);  // aged 두 건만 읽는다
        assertThat(step.getWriteCount()).isEqualTo(2);

        // 소멸액 = 읽기 시점이 아니라 처리 시점의 remaining
        assertThat(lot(agedPartial)).containsEntry("remaining", 0L).containsEntry("status", "EXPIRED");
        assertThat(expireAmountFor(agedPartial)).isEqualTo(1500); // 2000 - 사용 500
        assertThat(lot(agedFull)).containsEntry("remaining", 0L).containsEntry("status", "EXPIRED");
        assertThat(expireAmountFor(agedFull)).isEqualTo(700);
        assertThat(lot(alive)).containsEntry("remaining", 3000L).containsEntry("status", "ACTIVE");

        // 지갑별 잔액과 전역 불변식
        assertThat(balance(1)).isEqualTo(3000); // 2000 - 500 + 3000 - 1500
        assertThat(balance(2)).isZero();
        assertThat(query("SELECT COALESCE(SUM(remaining), 0) FROM point_lots"))
                .isEqualTo(query("SELECT COALESCE(SUM(balance), 0) FROM wallets"));
    }

    @Test
    @DisplayName("재수행 멱등성 — 완료 인스턴스는 거부되고(1차), 강제 재실행도 대상이 없다(2차)")
    void rerunIsIdempotentInTwoLayers() throws Exception {
        serverPost("/wallets", Map.of("userId", 1));
        long aged = earn(1, 1000, "w1-aged");
        age(aged, "1 day");

        assertThat(launch(null).getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(query("SELECT count(*) FROM ledger_entries WHERE type = 'EXPIRE'")).isEqualTo(1);

        // 1차 방어: 같은 asOf = 같은 JobInstance — JobRepository가 완료 재수행을 거부
        assertThatThrownBy(() -> launch(null))
                .isInstanceOf(JobInstanceAlreadyCompleteException.class);

        // 2차 방어: 식별 파라미터를 바꿔 강제로 새 인스턴스를 만들어도
        // remaining > 0 조건에 걸리는 로트가 없다 — 이중 만료는 데이터가 막는다
        JobExecution forced = launch(2L);
        assertThat(forced.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(forced.getStepExecutions().iterator().next().getReadCount()).isZero();
        assertThat(query("SELECT count(*) FROM ledger_entries WHERE type = 'EXPIRE'")).isEqualTo(1);
        assertThat(balance(1)).isZero();
    }

    private Map<String, Object> lot(long lotId) {
        Map<String, Object> row = jdbc.queryForMap(
                "SELECT remaining, status FROM point_lots WHERE id = ?", lotId);
        return Map.of("remaining", ((Number) row.get("remaining")).longValue(),
                "status", row.get("status"));
    }

    private long expireAmountFor(long lotId) {
        return query("SELECT amount FROM ledger_entries WHERE type = 'EXPIRE' AND ref_id = '"
                + lotId + "'");
    }

    private long balance(long userId) {
        return query("SELECT balance FROM wallets WHERE user_id = " + userId);
    }

    private long query(String sql) {
        return jdbc.queryForObject(sql, Long.class);
    }
}
