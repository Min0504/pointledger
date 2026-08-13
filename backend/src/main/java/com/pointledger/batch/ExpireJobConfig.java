package com.pointledger.batch;

import com.pointledger.ledger.LedgerService;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Map;
import javax.sql.DataSource;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.Step;
import org.springframework.batch.core.configuration.annotation.StepScope;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.batch.item.ItemWriter;
import org.springframework.batch.item.database.JdbcPagingItemReader;
import org.springframework.batch.item.database.Order;
import org.springframework.batch.item.database.builder.JdbcPagingItemReaderBuilder;
import org.springframework.batch.item.database.support.PostgresPagingQueryProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * 만료 Job (기획서 문제 4) — 수십만 로트를 청크(기본 500건) 단위로 소멸시킨다.
 *
 * 단일 거대 트랜잭션은 롤백 비용·락 범위·undo 부담이 재앙이다. 청크는 실패
 * 반경을 500건으로 제한하고, JobRepository 메타데이터(리더 위치)가 "중단 후
 * 이어서 재시작"을 가능하게 한다. 재시작이 안전한 이유는 두 겹이다:
 * ① 완료 청크는 커밋됐고 리더가 그 뒤에서 재개한다 ② 설령 같은 로트를 다시
 * 읽어도 remaining > 0 조건과 락 아래 재확인이 이중 만료를 걸러낸다.
 *
 * asOf(날짜)가 식별 파라미터다 — 같은 날짜의 재기동은 같은 JobInstance라서
 * 완료된 실행의 중복 수행을 JobRepository가 거부한다 (1차 방어).
 */
@Configuration
public class ExpireJobConfig {

    public static final String JOB_NAME = "expirePointsJob";

    @Bean
    public Job expirePointsJob(JobRepository jobRepository, Step expireLotsStep) {
        return new JobBuilder(JOB_NAME, jobRepository)
                .start(expireLotsStep)
                .build();
    }

    @Bean
    public Step expireLotsStep(JobRepository jobRepository,
            PlatformTransactionManager transactionManager,
            JdbcPagingItemReader<Long> expiredLotIdReader,
            ItemWriter<Long> expireLotWriter,
            @Value("${pointledger.batch.expire.chunk-size}") int chunkSize) {
        // 청크의 트랜잭션 경계: 리더가 chunkSize개를 모으면 라이터 호출부터
        // 커밋까지가 한 트랜잭션이다 — 실패 시 그 청크만 롤백된다
        return new StepBuilder("expireLotsStep", jobRepository)
                .<Long, Long>chunk(chunkSize, transactionManager)
                .reader(expiredLotIdReader)
                .writer(expireLotWriter)
                .build();
    }

    /**
     * 키셋 페이징 리더 — 부분 인덱스 (expires_at) WHERE remaining > 0을 탄다.
     *
     * offset이 아니라 정렬 키(id) 기준이라, 앞 청크가 처리한 행이 조건에서
     * 빠져나가도 페이지가 밀리지 않는다("움직이는 대상" 문제). 상태 저장도
     * 마지막 id뿐이라 재시작 복원이 가볍다.
     */
    @Bean
    @StepScope
    public JdbcPagingItemReader<Long> expiredLotIdReader(DataSource dataSource,
            @Value("#{jobParameters['asOf']}") String asOf,
            @Value("${pointledger.batch.expire.chunk-size}") int chunkSize) {
        PostgresPagingQueryProvider queryProvider = new PostgresPagingQueryProvider();
        queryProvider.setSelectClause("SELECT id");
        queryProvider.setFromClause("FROM point_lots");
        queryProvider.setWhereClause("WHERE expires_at < :cutoff AND remaining > 0");
        queryProvider.setSortKeys(Map.of("id", Order.ASCENDING));

        return new JdbcPagingItemReaderBuilder<Long>()
                .name("expiredLotIdReader")
                .dataSource(dataSource)
                .queryProvider(queryProvider)
                .parameterValues(Map.of("cutoff", java.sql.Timestamp.from(
                        LocalDate.parse(asOf).atStartOfDay(ZoneOffset.UTC).toInstant())))
                .pageSize(chunkSize)
                .rowMapper((rs, rowNum) -> rs.getLong("id"))
                .build();
    }

    /** 쓰기는 LedgerService.expireLots로 위임 — 잔액 갱신 관문은 여전히 한 곳이다 */
    @Bean
    public ItemWriter<Long> expireLotWriter(LedgerService ledgerService) {
        return chunk -> ledgerService.expireLots(new ArrayList<>(chunk.getItems()));
    }
}
