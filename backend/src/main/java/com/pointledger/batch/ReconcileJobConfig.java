package com.pointledger.batch;

import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.ZoneId;
import javax.sql.DataSource;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.Step;
import org.springframework.batch.core.configuration.annotation.StepScope;
import org.springframework.batch.core.job.builder.FlowBuilder;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.job.flow.Flow;
import org.springframework.batch.core.job.flow.FlowExecutionStatus;
import org.springframework.batch.core.job.flow.JobExecutionDecider;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.batch.core.step.tasklet.Tasklet;
import org.springframework.batch.item.database.JdbcBatchItemWriter;
import org.springframework.batch.item.database.builder.JdbcBatchItemWriterBuilder;
import org.springframework.batch.item.file.FlatFileItemReader;
import org.springframework.batch.item.file.builder.FlatFileItemReaderBuilder;
import org.springframework.batch.repeat.RepeatStatus;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.FileSystemResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * 일일 대사 Job (기획서 문제 5) — "틀리지 않게"가 아니라 "틀림을 발견하게".
 *
 * 3단 대조를 수행하고 불일치를 reconcile_issues에 남긴다. 자동 수정은 없다 —
 * 돈에서 자동 보정은 버그가 버그를 덮는 경로라, 탐지만 자동이고 수정은
 * 사람이 ADMIN_GRANT/REVOKE + 사유로 한다.
 *
 *   1단 SNAPSHOT_MISMATCH   wallet.balance ≠ SUM(원장) — 파생값 검산
 *   2단 INTERNAL/EXTERNAL_MISSING  외부(주문 서버) 기록 ↔ 원장 양방향 안티 조인
 *   3단 SETTLEMENT_MISMATCH 정산서 총액 ≠ 원장 재검산
 *
 * 흐름: 외부 CSV가 주어지면 스테이징 테이블에 적재(청크) 후 검사, 없으면
 * 바로 검사 — 파일 유무는 Decider가 분기한다. 파일을 행 단위로 비교하지 않고
 * 일단 DB에 넣는 이유: 누락 탐지는 집합 연산(안티 조인)이 정확하고,
 * "외부 기록"도 조회 가능한 데이터로 남는다.
 */
@Configuration
public class ReconcileJobConfig {

    public static final String JOB_NAME = "reconcileDailyJob";
    static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");

    /** CSV 한 행 — ref_id,entry_type,user_id,amount (record_date는 잡 파라미터) */
    public record ExternalRecordRow(String refId, String entryType, long userId, long amount) {
    }

    @Bean
    public Job reconcileDailyJob(JobRepository jobRepository,
            JobExecutionDecider externalFileDecider,
            Step clearExternalStep, Step loadExternalStep, Step reconcileChecksStep) {
        // 적재 경로와 생략 경로가 같은 검사 스텝에 합류한다
        Flow loadFlow = new FlowBuilder<Flow>("loadExternalFlow")
                .start(clearExternalStep)
                .next(loadExternalStep)
                .next(reconcileChecksStep)
                .build();
        Flow skipFlow = new FlowBuilder<Flow>("skipExternalFlow")
                .start(reconcileChecksStep)
                .build();

        return new JobBuilder(JOB_NAME, jobRepository)
                .start(externalFileDecider)
                .on("LOAD").to(loadFlow)
                .from(externalFileDecider).on("SKIP").to(skipFlow)
                .end()
                .build();
    }

    @Bean
    public JobExecutionDecider externalFileDecider() {
        return (jobExecution, stepExecution) -> {
            String file = jobExecution.getJobParameters().getString("externalFile");
            return new FlowExecutionStatus(file != null && !file.isBlank() ? "LOAD" : "SKIP");
        };
    }

    // ── 외부 기록 적재 ──────────────────────────────────────────────────

    /** 같은 날짜 재적재 대비 선삭제 — 적재를 몇 번 해도 결과가 같다 */
    @Bean
    public Step clearExternalStep(JobRepository jobRepository,
            PlatformTransactionManager transactionManager, Tasklet clearExternalTasklet) {
        return new StepBuilder("clearExternalStep", jobRepository)
                .tasklet(clearExternalTasklet, transactionManager)
                .build();
    }

    @Bean
    @StepScope
    public Tasklet clearExternalTasklet(DataSource dataSource,
            @Value("#{jobParameters['reconcileDate']}") String reconcileDate) {
        return (contribution, chunkContext) -> {
            new JdbcTemplate(dataSource).update(
                    "DELETE FROM external_order_records WHERE record_date = ?",
                    java.sql.Date.valueOf(LocalDate.parse(reconcileDate)));
            return RepeatStatus.FINISHED;
        };
    }

    @Bean
    public Step loadExternalStep(JobRepository jobRepository,
            PlatformTransactionManager transactionManager,
            FlatFileItemReader<ExternalRecordRow> externalCsvReader,
            JdbcBatchItemWriter<ExternalRecordRow> externalRecordWriter,
            @Value("${pointledger.batch.reconcile.chunk-size}") int chunkSize) {
        return new StepBuilder("loadExternalStep", jobRepository)
                .<ExternalRecordRow, ExternalRecordRow>chunk(chunkSize, transactionManager)
                .reader(externalCsvReader)
                .writer(externalRecordWriter)
                .build();
    }

    @Bean
    @StepScope
    public FlatFileItemReader<ExternalRecordRow> externalCsvReader(
            @Value("#{jobParameters['externalFile']}") String externalFile) {
        return new FlatFileItemReaderBuilder<ExternalRecordRow>()
                .name("externalCsvReader")
                .resource(new FileSystemResource(externalFile))
                .linesToSkip(1) // 헤더: ref_id,entry_type,user_id,amount
                .delimited()
                .names("refId", "entryType", "userId", "amount")
                .fieldSetMapper(fs -> new ExternalRecordRow(
                        fs.readString("refId"), fs.readString("entryType"),
                        fs.readLong("userId"), fs.readLong("amount")))
                .build();
    }

    @Bean
    @StepScope
    public JdbcBatchItemWriter<ExternalRecordRow> externalRecordWriter(DataSource dataSource,
            @Value("#{jobParameters['reconcileDate']}") String reconcileDate) {
        java.sql.Date date = java.sql.Date.valueOf(LocalDate.parse(reconcileDate));
        return new JdbcBatchItemWriterBuilder<ExternalRecordRow>()
                .dataSource(dataSource)
                .sql("""
                        INSERT INTO external_order_records
                            (record_date, ref_id, entry_type, user_id, amount)
                        VALUES (?, ?, ?, ?, ?)
                        """)
                .itemPreparedStatementSetter((row, ps) -> {
                    ps.setDate(1, date);
                    ps.setString(2, row.refId());
                    ps.setString(3, row.entryType());
                    ps.setLong(4, row.userId());
                    ps.setLong(5, row.amount());
                })
                .build();
    }

    // ── 3단 검사 ────────────────────────────────────────────────────────

    @Bean
    public Step reconcileChecksStep(JobRepository jobRepository,
            PlatformTransactionManager transactionManager, Tasklet reconcileChecksTasklet) {
        return new StepBuilder("reconcileChecksStep", jobRepository)
                .tasklet(reconcileChecksTasklet, transactionManager)
                .build();
    }

    /**
     * 검사는 전부 INSERT ... SELECT 안티 조인이다. 공통 규칙:
     *   - expected = 원장(진실) 기준, actual = 대조 대상 값 (누락이면 NULL)
     *   - NOT EXISTS로 "같은 대상의 미해결 이슈" 중복 적재를 막는다 —
     *     매일 도는 잡이 처리 전 이슈를 날마다 복제하면 큐가 쓰레기가 된다
     */
    @Bean
    @StepScope
    public Tasklet reconcileChecksTasklet(DataSource dataSource,
            @Value("#{jobParameters['reconcileDate']}") String reconcileDate,
            @Value("#{stepExecution.jobExecution.id}") Long jobRunId) {
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        return (contribution, chunkContext) -> {
            LocalDate date = LocalDate.parse(reconcileDate);
            java.sql.Date sqlDate = java.sql.Date.valueOf(date);
            Timestamp from = Timestamp.from(date.atStartOfDay(SEOUL).toInstant());
            Timestamp to = Timestamp.from(date.plusDays(1).atStartOfDay(SEOUL).toInstant());

            // 1단: 지갑 스냅샷 검산 — balance_after가 아니라 전체 SUM으로 다시 계산.
            // 원장이 진실이므로 expected가 원장 합, actual이 지갑 스냅샷이다.
            int snapshot = jdbc.update("""
                    INSERT INTO reconcile_issues (job_run_id, issue_type, wallet_id, expected, actual)
                    SELECT ?, 'SNAPSHOT_MISMATCH', w.id, COALESCE(l.total, 0), w.balance
                    FROM wallets w
                    LEFT JOIN (
                        SELECT wallet_id,
                               SUM(CASE WHEN type IN ('EARN', 'CANCEL', 'ADMIN_GRANT')
                                        THEN amount ELSE -amount END) AS total
                        FROM ledger_entries GROUP BY wallet_id
                    ) l ON l.wallet_id = w.id
                    WHERE w.balance <> COALESCE(l.total, 0)
                      AND NOT EXISTS (
                          SELECT 1 FROM reconcile_issues ri
                          WHERE ri.issue_type = 'SNAPSHOT_MISMATCH'
                            AND ri.wallet_id = w.id AND ri.resolved = false)
                    """, jobRunId);

            // 2단: 외부 기록 대조 — 해당 날짜의 외부 기록이 적재된 경우에만.
            // 이 가드가 없으면 "파일 없음"이 "전부 누락"으로 오탐된다.
            int internalMissing = 0;
            int externalMissing = 0;
            Boolean hasExternal = jdbc.queryForObject(
                    "SELECT EXISTS (SELECT 1 FROM external_order_records WHERE record_date = ?)",
                    Boolean.class, sqlDate);
            if (Boolean.TRUE.equals(hasExternal)) {
                // 매칭 키는 (ref_id, type, amount) — 금액이 다르면 양방향 누락
                // 한 쌍으로 드러난다 (금액 불일치를 별도 유형 없이 잡는 방식)
                internalMissing = jdbc.update("""
                        INSERT INTO reconcile_issues (job_run_id, issue_type, ref_id, expected, actual)
                        SELECT ?, 'INTERNAL_MISSING', e.ref_id, NULL, e.amount
                        FROM external_order_records e
                        WHERE e.record_date = ?
                          AND NOT EXISTS (
                              SELECT 1 FROM ledger_entries le
                              WHERE le.ref_type = 'ORDER' AND le.ref_id = e.ref_id
                                AND le.type = e.entry_type AND le.amount = e.amount)
                          AND NOT EXISTS (
                              SELECT 1 FROM reconcile_issues ri
                              WHERE ri.issue_type = 'INTERNAL_MISSING'
                                AND ri.ref_id = e.ref_id AND ri.resolved = false)
                        """, jobRunId, sqlDate);

                externalMissing = jdbc.update("""
                        INSERT INTO reconcile_issues (job_run_id, issue_type, ref_id, expected, actual)
                        SELECT ?, 'EXTERNAL_MISSING', le.ref_id, le.amount, NULL
                        FROM ledger_entries le
                        WHERE le.ref_type = 'ORDER' AND le.type IN ('EARN', 'REDEEM')
                          AND le.created_at >= ? AND le.created_at < ?
                          AND NOT EXISTS (
                              SELECT 1 FROM external_order_records e
                              WHERE e.record_date = ? AND e.ref_id = le.ref_id
                                AND e.entry_type = le.type AND e.amount = le.amount)
                          AND NOT EXISTS (
                              SELECT 1 FROM reconcile_issues ri
                              WHERE ri.issue_type = 'EXTERNAL_MISSING'
                                AND ri.ref_id = le.ref_id AND ri.resolved = false)
                        """, jobRunId, from, to, sqlDate);
            }

            // 3단: 정산서 재검산 — 총액을 원장에서 다시 만들어 비교한다.
            // DRAFT 불일치는 재정산으로, CONFIRMED 불일치는 사람이 수습한다.
            // ref_id에 정산일을 넣어 "어느 날짜의 불일치"인지 남긴다.
            int settlement = jdbc.update("""
                    INSERT INTO reconcile_issues (job_run_id, issue_type, merchant_id, ref_id, expected, actual)
                    SELECT ?, 'SETTLEMENT_MISMATCH', s.merchant_id, CAST(s.settle_date AS text),
                           COALESCE(x.total, 0), s.total_amount
                    FROM settlements s
                    LEFT JOIN (
                        SELECT le.merchant_id,
                               SUM(CASE WHEN le.type = 'REDEEM' THEN le.amount ELSE -le.amount END) AS total
                        FROM ledger_entries le
                        WHERE le.merchant_id IS NOT NULL
                          AND le.type IN ('REDEEM', 'CANCEL')
                          AND le.created_at >= ? AND le.created_at < ?
                        GROUP BY le.merchant_id
                    ) x ON x.merchant_id = s.merchant_id
                    WHERE s.settle_date = ?
                      AND s.total_amount <> COALESCE(x.total, 0)
                      AND NOT EXISTS (
                          SELECT 1 FROM reconcile_issues ri
                          WHERE ri.issue_type = 'SETTLEMENT_MISMATCH'
                            AND ri.merchant_id = s.merchant_id
                            AND ri.ref_id = CAST(s.settle_date AS text)
                            AND ri.resolved = false)
                    """, jobRunId, from, to, sqlDate);

            contribution.incrementWriteCount(
                    snapshot + internalMissing + externalMissing + settlement);
            return RepeatStatus.FINISHED;
        };
    }
}
