package com.pointledger.batch;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobParametersBuilder;
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 매일 새벽 전일 대사 기동 — 정산(05시)이 끝난 뒤(06시) 돈다. 순서가 중요하다:
 * 3단 검사(SETTLEMENT_MISMATCH)는 정산서가 있어야 의미가 있다.
 *
 * 외부 파일은 스케줄 기동에선 넣지 않는다(주문 서버 mock 파일은 수동/테스트
 * 경로로 공급) — 파일 없이도 1·3단 검사는 매일 도는 것이 가치다.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class ReconcileBatchScheduler {

    static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");

    private final JobLauncher jobLauncher;
    private final Job reconcileDailyJob;

    @Scheduled(cron = "${pointledger.batch.reconcile.cron}", zone = "Asia/Seoul")
    @SchedulerLock(name = ReconcileJobConfig.JOB_NAME, lockAtMostFor = "PT30M", lockAtLeastFor = "PT1M")
    public void runDailyReconcile() {
        try {
            launch(LocalDate.now(SEOUL).minusDays(1), null);
        } catch (Exception e) {
            log.error("대사 Job 기동 실패", e);
        }
    }

    /** externalFile이 null이면 외부 대조(2단)는 건너뛴다 — Decider가 분기 */
    public JobExecution launch(LocalDate reconcileDate, String externalFile) throws Exception {
        JobParametersBuilder params = new JobParametersBuilder()
                .addString("reconcileDate", reconcileDate.toString())
                .addString("triggeredAt", Instant.now().toString());
        if (externalFile != null && !externalFile.isBlank()) {
            params.addString("externalFile", externalFile, false);
        }
        return jobLauncher.run(reconcileDailyJob, params.toJobParameters());
    }
}
