package com.pointledger.concurrency;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.pointledger.support.IntegrationTest;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Map;
import javax.sql.DataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * 격리수준 거동 관찰 — 도메인 코드를 거치지 않고 순수 JDBC 두 커넥션으로
 * "읽고 → 검사하고 → 갱신"을 재연한다. 락 적용 후에도 이 테스트는 계속 남는다:
 * 애플리케이션이 왜 그 락을 필요로 하는지에 대한 실행 가능한 문서다 (기획서 §11).
 *
 * 요약 — 같은 잔액 5,000에서 3,000 사용 두 트랜잭션이 겹칠 때:
 *   READ COMMITTED : 둘 다 조용히 성공, 나중 커밋이 이긴다 → lost update (기본값의 함정)
 *   REPEATABLE READ: PostgreSQL이 두 번째 UPDATE를 40001로 거부 — 조용히 틀리는 대신
 *                    시끄럽게 실패한다. 해결이 아니라 "재시도 책임의 이전"이다.
 */
class IsolationLevelObservationTest extends IntegrationTest {

    @Autowired
    private DataSource dataSource;

    @Test
    @DisplayName("READ COMMITTED — check-then-act 두 트랜잭션이 모두 성공하고 갱신 하나가 소실된다")
    void readCommittedLosesUpdate() throws Exception {
        seedWallet(5000);

        try (Connection tx1 = open(Connection.TRANSACTION_READ_COMMITTED);
             Connection tx2 = open(Connection.TRANSACTION_READ_COMMITTED)) {
            long read1 = readBalance(tx1);
            long read2 = readBalance(tx2); // 둘 다 5,000을 본다 — 서로의 존재를 모른다
            assertThat(read1).isEqualTo(5000);
            assertThat(read2).isEqualTo(5000);

            writeBalance(tx1, read1 - 3000);
            tx1.commit();
            // tx2의 UPDATE는 tx1의 행 락을 기다렸다가, 커밋되면 "그 위에" 자기 값을 쓴다
            writeBalance(tx2, read2 - 3000);
            tx2.commit();
        }

        // 3,000 사용이 두 번인데 잔액은 한 번만 줄었다 — 이것이 lost update다
        assertThat(currentBalance()).isEqualTo(2000);
    }

    @Test
    @DisplayName("REPEATABLE READ — 두 번째 UPDATE가 직렬화 오류(40001)로 거부된다")
    void repeatableReadFailsLoudly() throws Exception {
        seedWallet(5000);

        try (Connection tx1 = open(Connection.TRANSACTION_REPEATABLE_READ);
             Connection tx2 = open(Connection.TRANSACTION_REPEATABLE_READ)) {
            readBalance(tx1);
            readBalance(tx2);

            writeBalance(tx1, 2000);
            tx1.commit();

            // 스냅샷 이후 다른 트랜잭션이 고친 행은 수정할 수 없다
            // → could not serialize access due to concurrent update
            assertThatThrownBy(() -> writeBalance(tx2, 2000))
                    .isInstanceOf(SQLException.class)
                    .satisfies(e -> assertThat(((SQLException) e).getSQLState()).isEqualTo("40001"));
            tx2.rollback();
        }

        // 조용히 틀리는 대신(RC) 한쪽이 실패했다 — 호출자 재시도가 필수라는 뜻이고,
        // 그 재시도 루프를 애플리케이션이 짊어질지(낙관적) 락으로 원천 차단할지(비관적)가 설계 선택
        assertThat(currentBalance()).isEqualTo(2000);
    }

    // ── JDBC 헬퍼 ────────────────────────────────────────────────────

    private void seedWallet(long balance) {
        serverPost("/wallets", Map.of("userId", 42));
        serverPost("/points/earn", Map.of("userId", 42, "amount", balance,
                "refType", "ORDER", "refId", "seed", "expireDays", 30));
    }

    private Connection open(int isolation) throws SQLException {
        Connection c = dataSource.getConnection();
        c.setAutoCommit(false);
        c.setTransactionIsolation(isolation);
        return c;
    }

    private long readBalance(Connection c) throws SQLException {
        try (PreparedStatement ps =
                c.prepareStatement("SELECT balance FROM wallets WHERE user_id = 42")) {
            ResultSet rs = ps.executeQuery();
            rs.next();
            return rs.getLong(1);
        }
    }

    private void writeBalance(Connection c, long balance) throws SQLException {
        try (PreparedStatement ps =
                c.prepareStatement("UPDATE wallets SET balance = ? WHERE user_id = 42")) {
            ps.setLong(1, balance);
            ps.executeUpdate();
        }
    }

    private long currentBalance() {
        return jdbc.queryForObject("SELECT balance FROM wallets WHERE user_id = 42", Long.class);
    }
}
