package com.pointledger.batch;

import com.pointledger.common.error.DomainException;
import com.pointledger.common.error.ErrorCode;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.time.LocalDate;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.repository.JobExecutionAlreadyRunningException;
import org.springframework.batch.core.repository.JobInstanceAlreadyCompleteException;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * 배치 수동 기동 (JWT, ROLE_ADMIN) — 스케줄을 놓친 날짜의 소급 실행,
 * 재정산, 외부 파일 대사가 용도다. 스케줄러와 같은 launch 진입점을 쓰므로
 * 파라미터 규약(식별/비식별)도 동일하다.
 *
 * 잡은 호출 스레드에서 동기 실행된다(기본 JobLauncher) — 정산·대사는 집합
 * SQL이라 수 초 안에 끝나므로 운영자가 결과 상태를 바로 받는 쪽이 낫다.
 */
@RestController
@RequiredArgsConstructor
public class AdminBatchController {

    private final ExpireBatchScheduler expireScheduler;
    private final SettleBatchScheduler settleScheduler;
    private final ReconcileBatchScheduler reconcileScheduler;

    public record ExpireRunRequest(@NotNull LocalDate asOf) {
    }

    public record SettleRunRequest(@NotNull LocalDate settleDate) {
    }

    /** externalFile — 서버 로컬 경로(운영자 전용 mock 공급 채널). 없으면 2단 생략 */
    public record ReconcileRunRequest(@NotNull LocalDate reconcileDate, String externalFile) {
    }

    public record JobRunView(Long executionId, String jobName, String status, String exitCode) {

        static JobRunView from(JobExecution e) {
            return new JobRunView(e.getId(), e.getJobInstance().getJobName(),
                    e.getStatus().name(), e.getExitStatus().getExitCode());
        }
    }

    @PostMapping("/admin/batch/expire/run")
    public JobRunView runExpire(@Valid @RequestBody ExpireRunRequest request) {
        try {
            return JobRunView.from(expireScheduler.launch(request.asOf()));
        } catch (JobInstanceAlreadyCompleteException e) {
            // 만료는 같은 asOf가 같은 JobInstance — 완료 재수행은 정상 거부다
            return new JobRunView(null, ExpireJobConfig.JOB_NAME,
                    "SKIPPED", "ALREADY_COMPLETE");
        } catch (Exception e) {
            throw launchFailure(e);
        }
    }

    @PostMapping("/admin/batch/settle/run")
    public JobRunView runSettle(@Valid @RequestBody SettleRunRequest request) {
        try {
            return JobRunView.from(settleScheduler.launch(request.settleDate()));
        } catch (Exception e) {
            throw launchFailure(e);
        }
    }

    @PostMapping("/admin/batch/reconcile/run")
    public JobRunView runReconcile(@Valid @RequestBody ReconcileRunRequest request) {
        try {
            return JobRunView.from(
                    reconcileScheduler.launch(request.reconcileDate(), request.externalFile()));
        } catch (Exception e) {
            throw launchFailure(e);
        }
    }

    /**
     * 기동 자체가 거부된 경우만 예외다(동시 실행 등). 스텝이 실패한 실행은
     * jobLauncher가 FAILED 상태의 JobExecution을 반환하므로 위에서 그대로
     * 보인다 — 운영자는 status 필드로 성패를 판단한다.
     */
    private DomainException launchFailure(Exception e) {
        String cause = e instanceof JobExecutionAlreadyRunningException
                ? "ALREADY_RUNNING" : e.getClass().getSimpleName();
        return new DomainException(ErrorCode.BATCH_JOB_FAILED, Map.of("cause", cause));
    }
}
