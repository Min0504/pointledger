package com.pointledger.settlement;

import java.time.LocalDate;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface SettlementRepository extends JpaRepository<Settlement, Long> {

    /**
     * 운영자 정산 조회 — 두 필터 모두 선택. NULL 파라미터는 명시적 CAST가
     * 필요하다: PostgreSQL은 바인드 시점에 null의 타입을 못 정한다.
     */
    @Query(value = """
            SELECT * FROM settlements
            WHERE (CAST(:settleDate AS date) IS NULL OR settle_date = :settleDate)
              AND (CAST(:merchantId AS bigint) IS NULL OR merchant_id = :merchantId)
            ORDER BY settle_date DESC, merchant_id
            """, nativeQuery = true)
    List<Settlement> search(
            @Param("settleDate") LocalDate settleDate, @Param("merchantId") Long merchantId);
}
