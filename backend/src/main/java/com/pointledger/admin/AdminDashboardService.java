package com.pointledger.admin;

import com.pointledger.admin.dto.DashboardDtos.DashboardResponse;
import com.pointledger.admin.dto.DashboardDtos.TypeStat;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 대시보드 집계 — 도메인 서비스가 아니라 읽기 전용 리포트 계층이다.
 * 엔티티를 거치지 않고 집합 SQL로 바로 읽는다: 화면 한 장을 위해 수만 행을
 * 영속성 컨텍스트에 올리는 것이 낭비고, 집계는 DB가 가장 잘한다.
 */
@Service
@RequiredArgsConstructor
public class AdminDashboardService {

    static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");

    private final JdbcTemplate jdbc;

    @Transactional(readOnly = true)
    public DashboardResponse today() {
        LocalDate today = LocalDate.now(SEOUL);
        Timestamp from = Timestamp.from(today.atStartOfDay(SEOUL).toInstant());
        Timestamp to = Timestamp.from(today.plusDays(1).atStartOfDay(SEOUL).toInstant());

        List<TypeStat> byType = jdbc.query("""
                SELECT type, count(*) AS cnt, SUM(amount) AS total
                FROM ledger_entries
                WHERE created_at >= ? AND created_at < ?
                GROUP BY type
                ORDER BY type
                """,
                (rs, i) -> new TypeStat(rs.getString("type"), rs.getLong("cnt"), rs.getLong("total")),
                from, to);

        long circulating = jdbc.queryForObject(
                "SELECT COALESCE(SUM(balance), 0) FROM wallets", Long.class);
        long wallets = jdbc.queryForObject(
                "SELECT count(*) FROM wallets", Long.class);
        long unresolvedIssues = jdbc.queryForObject(
                "SELECT count(*) FROM reconcile_issues WHERE resolved = false", Long.class);
        long draftSettlements = jdbc.queryForObject(
                "SELECT count(*) FROM settlements WHERE status = 'DRAFT'", Long.class);

        return new DashboardResponse(
                today, byType, circulating, wallets, unresolvedIssues, draftSettlements);
    }
}
