package com.pointledger.batch;

import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.ZoneId;
import javax.sql.DataSource;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.Step;
import org.springframework.batch.core.configuration.annotation.StepScope;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.batch.core.step.tasklet.Tasklet;
import org.springframework.batch.repeat.RepeatStatus;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * 일일 정산 Job (기획서 문제 5) — 가맹점별 사용(REDEEM)·취소(CANCEL)를 집계해
 * 정산서를 만든다.
 *
 * 만료 Job(청크)과 달리 태스크릿 + 집합 SQL 한 번이다: 정산은 행 단위 도메인
 * 로직이 아니라 GROUP BY 집계라, DB 집합 연산이 가장 정확하고 빠르다. 단일
 * 트랜잭션이므로 "이어서 재시작"이 필요 없고, 실패하면 통째로 롤백 후 처음부터
 * 다시가 곧 재시작이다.
 *
 * 멱등성은 배치 코드가 아니라 스키마가 지킨다 (기획서 §10-2):
 *   - UNIQUE (merchant_id, settle_date) — 재실행·이중 기동에도 정산서는 하나
 *   - UNIQUE (ledger_entry_id) — 한 거래가 두 정산서에 들어갈 수 없다
 *
 * 재실행 정책: DRAFT는 지우고 다시 집계한다(늦게 도착한 취소 반영),
 * CONFIRMED는 동결 — 이후의 어긋남은 대사가 SETTLEMENT_MISMATCH로 발견하고
 * 익일 차감 정산으로 수습한다.
 */
@Configuration
public class SettleJobConfig {

    public static final String JOB_NAME = "settleDailyJob";
    static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");

    @Bean
    public Job settleDailyJob(JobRepository jobRepository, Step settleStep) {
        return new JobBuilder(JOB_NAME, jobRepository)
                .start(settleStep)
                .build();
    }

    @Bean
    public Step settleStep(JobRepository jobRepository,
            PlatformTransactionManager transactionManager, Tasklet settleTasklet) {
        return new StepBuilder("settleStep", jobRepository)
                .tasklet(settleTasklet, transactionManager)
                .build();
    }

    @Bean
    @StepScope
    public Tasklet settleTasklet(DataSource dataSource,
            @Value("#{jobParameters['settleDate']}") String settleDate) {
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        return (contribution, chunkContext) -> {
            LocalDate date = LocalDate.parse(settleDate);
            // 정산일의 경계는 KST 자정 — settle_date는 영업일 개념이다
            Timestamp from = Timestamp.from(date.atStartOfDay(SEOUL).toInstant());
            Timestamp to = Timestamp.from(date.plusDays(1).atStartOfDay(SEOUL).toInstant());

            // 1) 정산서 뼈대 — ON CONFLICT DO NOTHING이 재실행·동시 기동 멱등성.
            //    CANCEL만 있는 날(취소 이월)도 정산서가 생겨야 차감이 기록된다.
            //    SELECT 목록의 파라미터는 비교 문맥이 없어 타입 추론이 안 된다 —
            //    명시적 CAST가 필요하다 (컬럼 비교 위치의 ?는 추론된다).
            jdbc.update("""
                    INSERT INTO settlements (merchant_id, settle_date, total_amount)
                    SELECT DISTINCT le.merchant_id, CAST(? AS date), 0
                    FROM ledger_entries le
                    WHERE le.merchant_id IS NOT NULL
                      AND le.type IN ('REDEEM', 'CANCEL')
                      AND le.created_at >= ? AND le.created_at < ?
                    ON CONFLICT (merchant_id, settle_date) DO NOTHING
                    """, java.sql.Date.valueOf(date), from, to);

            // 2) DRAFT 라인 재집계 — 지우고 다시 넣는다. 늦게 도착한 원장이
            //    반영되는 경로이자, 부분 실패 후 재실행이 안전한 이유다.
            jdbc.update("""
                    DELETE FROM settlement_lines sl
                    USING settlements s
                    WHERE sl.settlement_id = s.id
                      AND s.settle_date = ? AND s.status = 'DRAFT'
                    """, java.sql.Date.valueOf(date));

            // REDEEM은 +, CANCEL은 − (부호는 라인에 고정 — 대사가 재검산할 근거).
            // ON CONFLICT (ledger_entry_id) DO NOTHING: 이미 다른 정산서(전일
            // CONFIRMED 등)에 들어간 거래는 건너뛴다 — 이중 정산 차단의 실체.
            int lines = jdbc.update("""
                    INSERT INTO settlement_lines (settlement_id, ledger_entry_id, amount)
                    SELECT s.id, le.id,
                           CASE WHEN le.type = 'REDEEM' THEN le.amount ELSE -le.amount END
                    FROM ledger_entries le
                    JOIN settlements s
                      ON s.merchant_id = le.merchant_id AND s.settle_date = ?
                    WHERE le.merchant_id IS NOT NULL
                      AND le.type IN ('REDEEM', 'CANCEL')
                      AND le.created_at >= ? AND le.created_at < ?
                      AND s.status = 'DRAFT'
                    ON CONFLICT (ledger_entry_id) DO NOTHING
                    """, java.sql.Date.valueOf(date), from, to);

            // 3) 총액 = 라인 합 — 별도 계산이 아니라 라인에서 파생시킨다
            //    (원장→잔액과 같은 원칙: 집계는 항상 원본에서 다시 만든다)
            jdbc.update("""
                    UPDATE settlements s
                    SET total_amount = COALESCE(
                            (SELECT SUM(sl.amount) FROM settlement_lines sl
                             WHERE sl.settlement_id = s.id), 0)
                    WHERE s.settle_date = ? AND s.status = 'DRAFT'
                    """, java.sql.Date.valueOf(date));

            contribution.incrementWriteCount(lines);
            return RepeatStatus.FINISHED;
        };
    }
}
