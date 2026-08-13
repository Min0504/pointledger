package com.pointledger.wallet;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 잔액 스냅샷. 진실은 원장(ledger_entries)이고 balance는 조회 성능을 위한 파생값이다 —
 * 이 비정규화의 검증 의무는 대사 배치(Phase 6)가 진다 (기획서 §7 문제 3).
 *
 * balance를 바꾸는 공개 메서드를 두지 않는 것이 의도다: 잔액 변경은 반드시
 * 원장 INSERT와 같은 트랜잭션이어야 하므로 LedgerService만이 apply()를 호출한다.
 */
@Entity
@Table(name = "wallets")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Wallet {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false, unique = true)
    private Long userId;

    @Column(nullable = false)
    private long balance;

    /** 낙관적 락 실험용(@Version은 experiment 브랜치에서만 활성) — 기본 전략은 비관적 락 */
    @Column(nullable = false)
    private int version;

    @Column(name = "created_at", nullable = false, updatable = false, insertable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    public Wallet(Long userId) {
        this.userId = userId;
        this.balance = 0L;
    }

    /**
     * 원장 기록과 같은 트랜잭션에서만 호출된다 — 호출처는 LedgerService 하나여야 한다
     * (지갑 잔액을 직접 UPDATE하는 코드 경로를 없애는 구조 원칙, 기획서 §4).
     * 음수 결과는 DB CHECK(balance >= 0)가 최후 방어선으로 한 번 더 막는다.
     */
    public void apply(long delta) {
        this.balance += delta;
    }

    @PreUpdate
    void touch() {
        this.updatedAt = Instant.now();
    }
}
