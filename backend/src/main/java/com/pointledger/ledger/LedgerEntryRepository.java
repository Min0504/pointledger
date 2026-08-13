package com.pointledger.ledger;

import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface LedgerEntryRepository extends JpaRepository<LedgerEntry, Long> {

    /**
     * 커서 페이지네이션 — (wallet_id, id DESC) 인덱스를 그대로 타므로
     * 이력이 수십만 건이어도 offset처럼 앞부분을 스캔하지 않는다 (기획서 §8).
     * 첫 페이지는 cursor = Long.MAX_VALUE로 호출한다 — "NULL이면 전체" 분기를
     * 쿼리에 넣으면 PostgreSQL이 파라미터 타입을 못 정하는 문제가 있어 값으로 통일.
     */
    @Query("""
            SELECT e FROM LedgerEntry e
            WHERE e.walletId = :walletId AND e.id < :cursor
            ORDER BY e.id DESC
            """)
    List<LedgerEntry> pageByWallet(
            @Param("walletId") Long walletId, @Param("cursor") long cursor, Pageable pageable);
}
