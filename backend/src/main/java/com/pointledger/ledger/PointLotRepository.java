package com.pointledger.ledger;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
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

    /**
     * FIFO 차감 대상 — 만료 임박 순. 부분 인덱스 idx_lots_wallet_fifo
     * (wallet_id, expires_at, id) WHERE remaining > 0을 그대로 탄다.
     *
     * 만료됐지만 소멸 전인 로트도 포함한다(정렬상 맨 앞) — 만료의 효력은 배치가
     * EXPIRE를 기장하는 순간이라고 정의해야 로트 합계와 잔액이 항상 일치한다.
     * 로트 행 자체는 잠그지 않는다: 로트를 변경하는 모든 경로(사용·취소·지급·
     * 회수·만료 배치)가 지갑 행 락을 먼저 잡는 규약이므로 지갑 락이 곧 로트 락이다.
     */
    @Query("""
            SELECT l FROM PointLot l
            WHERE l.walletId = :walletId AND l.remaining > 0
            ORDER BY l.expiresAt, l.id
            """)
    List<PointLot> findFifoConsumable(@Param("walletId") Long walletId);

    /** 만료 배치 청크의 지갑 그룹핑 — 오름차순 잠금 순서가 데드락을 예방한다 */
    @Query("SELECT DISTINCT l.walletId FROM PointLot l WHERE l.id IN :ids ORDER BY l.walletId")
    List<Long> findWalletIdsByIdIn(@Param("ids") Collection<Long> ids);

    /**
     * 지갑 락을 잡은 뒤의 신선한 재조회용 — 리더가 id를 읽은 시점과 지갑 락
     * 획득 사이에 사용자가 로트를 소진했을 수 있다. 락 아래에서 다시 읽어야
     * remaining이 진실이다 (배치 vs 온라인 경합, 기획서 문제 4).
     */
    @Query("SELECT l FROM PointLot l WHERE l.walletId = :walletId AND l.id IN :ids ORDER BY l.id")
    List<PointLot> findByWalletIdAndIdIn(
            @Param("walletId") Long walletId, @Param("ids") Collection<Long> ids);
}
