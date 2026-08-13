package com.pointledger.idempotency;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pointledger.auth.Sha256;
import com.pointledger.common.error.DomainException;
import com.pointledger.common.error.ErrorCode;
import com.pointledger.common.error.ErrorResponse;
import java.time.Duration;
import java.util.Map;
import java.util.function.Supplier;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 멱등 실행의 관문 (기획서 문제 2 — Stripe/Toss Payments 패턴).
 *
 * 두 단계 트랜잭션 구조가 핵심이다:
 *   1) [독립 커밋] 키 INSERT로 선점 — 동시 중복 요청은 PK 충돌로 즉시 드러난다.
 *      비즈니스 트랜잭션과 합치면 커밋 전까지 다른 요청이 선점을 볼 수 없어
 *      같은 키 두 개가 나란히 실행되는 구멍이 생긴다.
 *   2) [비즈니스 트랜잭션] 작업 실행 + 같은 트랜잭션에서 DONE·응답 저장 —
 *      "부수효과는 커밋됐는데 DONE 기록 전에 죽어서 재시도가 이중 실행"이라는
 *      최악의 창을 원천 제거한다. 작업과 완료 표시는 전부-또는-전무.
 *
 * 결과 규약: 도메인 에러(4xx)도 DONE으로 저장한다 — 같은 키는 언제나 같은 답.
 * 예상 밖 예외(5xx)는 키를 지워 호출자의 진짜 재시도를 허용한다.
 * 처리 중 프로세스가 죽으면 IN_PROGRESS 행이 남는데, 비즈니스 트랜잭션이
 * 롤백됐으므로 부수효과는 없다 — STALE_AFTER가 지나면 재시도가 인수한다.
 */
@Component
@RequiredArgsConstructor
public class IdempotencyManager {

    /** 이보다 오래된 IN_PROGRESS는 죽은 요청으로 판정 — 부수효과 없음이 보장되므로 인수 가능 */
    static final Duration STALE_AFTER = Duration.ofSeconds(60);

    private final NamedParameterJdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final ObjectMapper objectMapper;

    /**
     * @param key           Idempotency-Key 헤더 값
     * @param endpoint      키 충돌 판정에 포함할 경로 식별자 — 같은 키를 다른 API에 쓰면 422
     * @param body          요청 바디 (해시 대조용)
     * @param successStatus 성공 시 HTTP 상태 — 재생 응답이 원본과 같은 상태를 갖도록
     * @param action        비즈니스 로직 — 내부에서 @Transactional로 이 트랜잭션에 참여한다
     */
    public <T> T execute(
            String key, String endpoint, Object body, HttpStatus successStatus, Supplier<T> action) {
        String requestHash = Sha256.hex(endpoint + "\n" + toJson(body));

        if (!tryAcquire(key, requestHash)) {
            handleExisting(key, requestHash); // 항상 예외로 끝난다 (재생 포함)
        }

        try {
            // 작업과 DONE 표시를 한 트랜잭션으로 — 커밋되면 반드시 재생 가능하다
            return tx.execute(status -> {
                T result = action.get();
                markDone(key, successStatus.value(), toJson(result));
                return result;
            });
        } catch (DomainException e) {
            // 결정적 실패(잔액 부족 등)도 이 키의 확정 결과다 — 재시도해도 같은 답을 재생
            markDoneNow(key, e.getCode().getStatus().value(),
                    toJson(ErrorResponse.of(e.getCode(), e.getDetails())));
            throw e;
        } catch (RuntimeException e) {
            // 예상 밖 실패는 결과가 아니다 — 키를 반납해 진짜 재시도를 허용
            release(key);
            throw e;
        }
    }

    /** 선점 시도. 죽은 요청(오래된 IN_PROGRESS)이면 인수한다. */
    private boolean tryAcquire(String key, String requestHash) {
        int inserted = jdbc.update("""
                INSERT INTO idempotency_requests (idem_key, request_hash)
                VALUES (:key, :hash)
                ON CONFLICT (idem_key) DO NOTHING
                """, Map.of("key", key, "hash", requestHash));
        if (inserted == 1) {
            return true;
        }
        int takenOver = jdbc.update("""
                UPDATE idempotency_requests
                SET request_hash = :hash, created_at = now(), expires_at = now() + interval '7 days'
                WHERE idem_key = :key AND status = 'IN_PROGRESS'
                  AND created_at < now() - make_interval(secs => :staleSec)
                """, Map.of("key", key, "hash", requestHash, "staleSec", STALE_AFTER.toSeconds()));
        return takenOver == 1;
    }

    /** 선점 실패 시 기존 행 해석 — 처리 중 409 / 바디 불일치 422 / 완료 응답 재생 */
    private void handleExisting(String key, String requestHash) {
        Map<String, Object> row = jdbc.queryForMap("""
                SELECT request_hash, status, response_status, response_body::text AS body
                FROM idempotency_requests WHERE idem_key = :key
                """, Map.of("key", key));

        if ("IN_PROGRESS".equals(row.get("status"))) {
            throw new DomainException(ErrorCode.IDEMPOTENT_IN_PROGRESS);
        }
        if (!requestHash.equals(((String) row.get("request_hash")).trim())) {
            throw new DomainException(ErrorCode.IDEMPOTENCY_KEY_REUSED);
        }
        throw new StoredResponseException(
                (Integer) row.get("response_status"), (String) row.get("body"));
    }

    /** 진행 중인 비즈니스 트랜잭션 안에서 호출 — 작업과 함께 커밋된다 */
    private void markDone(String key, int status, String bodyJson) {
        jdbc.update("""
                UPDATE idempotency_requests
                SET status = 'DONE', response_status = :status, response_body = :body::jsonb
                WHERE idem_key = :key
                """, Map.of("key", key, "status", status, "body", bodyJson));
    }

    /** 도메인 에러 경로 — 비즈니스 트랜잭션은 이미 롤백됐으므로 새 트랜잭션으로 기록 */
    private void markDoneNow(String key, int status, String bodyJson) {
        tx.executeWithoutResult(s -> markDone(key, status, bodyJson));
    }

    private void release(String key) {
        tx.executeWithoutResult(s -> jdbc.update(
                "DELETE FROM idempotency_requests WHERE idem_key = :key", Map.of("key", key)));
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("응답 직렬화 실패 — 멱등 재생이 불가능해진다", e);
        }
    }
}
