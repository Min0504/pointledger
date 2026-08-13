package com.pointledger.idempotency;

import static org.assertj.core.api.Assertions.assertThat;

import com.pointledger.support.IntegrationTest;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/**
 * 재현 테스트 (기획서 문제 2) — 이 커밋에서는 실패한다.
 *
 * 네트워크는 "요청이 처리됐는지"를 알려주지 않는다. 타임아웃을 받은 호출자가
 * 재시도하는 것은 at-least-once 세계에서 유일하게 합리적인 행동이고, 그 결과
 * 같은 요청이 두 번 도착하는 것은 예외 상황이 아니라 일상이다. 중복을 제거할
 * 책임은 재시도하는 쪽이 아니라 받는 쪽(우리)에 있다 — 지금은 그 장치가 없어서
 * 같은 적립이 두 번 기장되고, 원장과 잔액이 모두 두 배가 된다.
 */
class IdempotencyIntegrationTest extends IntegrationTest {

    @Test
    @DisplayName("타임아웃 재시도 — 같은 적립 요청이 두 번 도착하면 이중 적립된다")
    void retriedEarnMustNotDoubleCredit() {
        serverPost("/wallets", Map.of("userId", 7));
        Map<String, Object> earn = Map.of("userId", 7, "amount", 1000,
                "refType", "ORDER", "refId", "ord-1", "expireDays", 30);

        // 원 요청 — 서버는 정상 처리했지만 응답이 유실됐다고 가정
        ResponseEntity<Map<String, Object>> first = serverPost("/points/earn", earn);
        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.CREATED);

        // 호출자의 재시도 — 완전히 같은 요청
        serverPost("/points/earn", earn);

        // 1,000P 적립 의도였는데 원장에 EARN이 두 줄, 잔액은 2,000이 된다
        assertThat(earnEntryCount()).isEqualTo(1);
        assertThat(walletBalance()).isEqualTo(1000);
    }

    private long earnEntryCount() {
        return jdbc.queryForObject(
                "SELECT count(*) FROM ledger_entries WHERE type = 'EARN'", Long.class);
    }

    private long walletBalance() {
        return jdbc.queryForObject("SELECT balance FROM wallets WHERE user_id = 7", Long.class);
    }
}
