package com.pointledger.ledger;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 적립 단위(로트). 적립 1건 = 로트 1개이며, 만료는 로트 단위로 일어난다.
 * FIFO 차감·부분 취소가 "어느 적립분이 얼마나 남았는가"를 요구하므로
 * 잔액 총합만으로는 부족하다 — 이 테이블이 만료·취소 정확성의 근거다 (기획서 §5).
 */
@Entity
@Table(name = "point_lots")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PointLot {

    public enum Status { ACTIVE, EXHAUSTED, EXPIRED }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "wallet_id", nullable = false)
    private Long walletId;

    /** 이 로트를 만든 적립 원장 엔트리 — 감사 시 "이 잔액이 어디서 왔나"의 답 */
    @Column(name = "earn_entry_id", nullable = false, unique = true)
    private Long earnEntryId;

    @Column(name = "initial_amount", nullable = false)
    private long initialAmount;

    @Column(nullable = false)
    private long remaining;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Status status = Status.ACTIVE;

    @Column(name = "created_at", nullable = false, updatable = false, insertable = false)
    private Instant createdAt;

    public PointLot(Long walletId, Long earnEntryId, long amount, Instant expiresAt) {
        this.walletId = walletId;
        this.earnEntryId = earnEntryId;
        this.initialAmount = amount;
        this.remaining = amount;
        this.expiresAt = expiresAt;
        this.status = Status.ACTIVE;
    }

    /** 만료 판정 — 경계 포함(expires_at == now도 만료). 소멸(EXPIRED 전환)은 배치의 몫이다 */
    public boolean isExpiredAt(Instant now) {
        return status == Status.EXPIRED || !expiresAt.isAfter(now);
    }

    public void consume(long value) {
        if (value <= 0 || value > remaining) {
            throw new IllegalArgumentException(
                    "로트 차감 초과: remaining=" + remaining + ", requested=" + value);
        }
        this.remaining -= value;
        if (this.remaining == 0) {
            this.status = Status.EXHAUSTED;
        }
    }

    /**
     * 만료 소멸 — 배치 전용. 지금 남은 만큼만 EXPIRE로 나간다 (읽기 시점이
     * 아니라 지갑 락 아래 재확인 시점의 remaining — 온라인 사용과의 경합 안전).
     */
    public long expire() {
        long amount = this.remaining;
        this.remaining = 0;
        this.status = Status.EXPIRED;
        return amount;
    }

    /** 취소 복원 — 만료된 로트에는 호출하지 않는다(유예 로트 정책, LotPlanner 참조) */
    public void restore(long value) {
        if (value <= 0 || this.remaining + value > initialAmount) {
            throw new IllegalArgumentException(
                    "로트 복원 초과: remaining=" + remaining + ", initial=" + initialAmount
                            + ", requested=" + value);
        }
        this.remaining += value;
        this.status = Status.ACTIVE;
    }
}
