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
 * 매일 새벽 전일 정산 기동 — 다중 인스턴스 중복 기동은 ShedLock이 차단한다.
 *
 * 만료 Job과 달리 triggeredAt을 식별 파라미터로 넣는다: 같은 날짜의 재정산은
 * 오류가 아니라 정당한 운영 행위(늦게 도착한 취소 반영)라서, JobInstance
 * 중복 거부에 맡기지 않고 매 기동을 새 인스턴스로 만든다. 그래도 안전한
 * 이유는 멱등성이 스키마에 있기 때문이다 — UNIQUE (merchant_id, settle_date)와
 * DRAFT 재집계(delete+insert)가 몇 번을 돌려도 같은 결과를 보장한다.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class SettleBatchScheduler {

    static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");

    private final JobLauncher jobLauncher;
    private final Job settleDailyJob;

    @Scheduled(cron = "${pointledger.batch.settle.cron}", zone = "Asia/Seoul")
    @SchedulerLock(name = SettleJobConfig.JOB_NAME, lockAtMostFor = "PT30M", lockAtLeastFor = "PT1M")
    public void runDailySettle() {
        try {
            launch(LocalDate.now(SEOUL).minusDays(1));
        } catch (Exception e) {
            log.error("정산 Job 기동 실패", e);
        }
    }

    /** 스케줄러 밖(테스트·수동 재정산)에서도 같은 진입점을 쓴다 */
    public JobExecution launch(LocalDate settleDate) throws Exception {
        return jobLauncher.run(settleDailyJob, new JobParametersBuilder()
                .addString("settleDate", settleDate.toString())
                .addString("triggeredAt", Instant.now().toString())
                .toJobParameters());
    }
}
