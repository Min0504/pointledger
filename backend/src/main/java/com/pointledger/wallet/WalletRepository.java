package com.pointledger.wallet;

import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface WalletRepository extends JpaRepository<Wallet, Long> {

    Optional<Wallet> findByUserId(Long userId);

    /**
     * SELECT ... FOR UPDATE — 지갑 단위 쓰기 직렬화.
     *
     * READ COMMITTED에서는 두 커넥션이 같은 잔액을 읽고 각자 갱신해 lost update가
     * 발생한다 (재현: RedeemConcurrencyTest, 20건 중 20건 성공/기대 5건). 포인트
     * 사용은 잔액 검증 → 로트 FIFO 차감 → 원장 INSERT → 잔액 갱신의 다단계라
     * SeatLock식 한 줄 조건부 UPDATE로는 못 풀고, 행 락 직렬화가 정확성 대비
     * 가장 단순하다. 핫스팟이 자원(좌석)이 아니라 사용자 자신이라 같은 지갑의
     * 동시 요청은 드물고 락 경합 비용도 낮다 — 도메인이 다르면 답도 다르다.
     *
     * 낙관적 락(@Version + 재시도) 비교 구현은 experiment/optimistic-retry 브랜치.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT w FROM Wallet w WHERE w.userId = :userId")
    Optional<Wallet> findByUserIdForUpdate(@Param("userId") Long userId);
}
