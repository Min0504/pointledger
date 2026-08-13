package com.pointledger.ledger;

import java.time.Instant;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PointLotRepository extends JpaRepository<PointLot, Long> {

    /** 30일 내 만료 예정액 — 부분 인덱스 (expires_at) WHERE remaining > 0 활용 */
    @Query("""
            SELECT COALESCE(SUM(l.remaining), 0) FROM PointLot l
            WHERE l.walletId = :walletId AND l.remaining > 0
              AND l.expiresAt > :now AND l.expiresAt <= :until
            """)
    long sumExpiringBetween(
            @Param("walletId") Long walletId, @Param("now") Instant now, @Param("until") Instant until);
}
