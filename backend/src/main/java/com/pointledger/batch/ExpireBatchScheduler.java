package com.pointledger.batch;

import java.time.LocalDate;
import java.time.ZoneId;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobParametersBuilder;
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.batch.core.repository.JobInstanceAlreadyCompleteException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 매일 새벽 만료 Job 기동 — 다중 인스턴스 중복 기동은 ShedLock이 차단한다.
 *
 * 계층 방어 (기획서 §10-2): ① ShedLock 상호 배제 ② 같은 asOf는 같은
 * JobInstance라 JobRepository가 완료 재수행 거부 ③ 락이 다 뚫려도
 * remaining > 0 조건 + 락 아래 재확인이 이중 만료를 데이터에서 막는다.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class ExpireBatchScheduler {

    static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");

    private final JobLauncher jobLauncher;
    private final Job expirePointsJob;

    @Scheduled(cron = "${pointledger.batch.expire.cron}", zone = "Asia/Seoul")
    @SchedulerLock(name = ExpireJobConfig.JOB_NAME, lockAtMostFor = "PT30M", lockAtLeastFor = "PT1M")
    public void runDailyExpiry() {
        try {
            launch(LocalDate.now(SEOUL));
        } catch (JobInstanceAlreadyCompleteException e) {
            log.info("오늘 만료 Job은 이미 완료 — 건너뜀");
        } catch (Exception e) {
            log.error("만료 Job 기동 실패", e);
        }
    }

    /** 스케줄러 밖(테스트·수동 운영)에서도 같은 진입점을 쓴다 */
    public JobExecution launch(LocalDate asOf) throws Exception {
        return jobLauncher.run(expirePointsJob, new JobParametersBuilder()
                .addString("asOf", asOf.toString())
                .toJobParameters());
    }
}
