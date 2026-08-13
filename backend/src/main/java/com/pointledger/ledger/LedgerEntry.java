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
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 원장 — append-only. 이 엔티티에 setter가 하나도 없는 것, 그리고 DB 트리거가
 * UPDATE/DELETE를 거부하는 것이 같은 원칙의 두 계층이다: 기록은 고치지 않는다.
 * 정정이 필요하면 반대 방향 엔트리를 추가한다 (회계의 붉은 줄 원리).
 */
@Entity
@Table(name = "ledger_entries")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class LedgerEntry {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "wallet_id", nullable = false)
    private Long walletId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private LedgerEntryType type;

    /** 항상 양수 — 방향은 type이 결정 */
    @Column(nullable = false)
    private long amount;

    /** 기록 시점 잔액 — 대사 불일치 지점을 이력에서 이분탐색할 수 있게 하는 스냅샷 */
    @Column(name = "balance_after", nullable = false)
    private long balanceAfter;

    @Column(name = "ref_type", length = 20)
    private String refType;

    @Column(name = "ref_id", length = 64)
    private String refId;

    @Column(name = "idempotency_key", length = 64)
    private String idempotencyKey;

    /** ADMIN 계열은 스키마 CHECK로 NOT NULL 강제 — 사유 없는 수동 조작은 기록될 수 없다 */
    @Column(length = 200)
    private String reason;

    @Column(name = "created_by", nullable = false, length = 64)
    private String createdBy;

    @Column(name = "created_at", nullable = false, updatable = false, insertable = false)
    private Instant createdAt;

    @Builder
    private LedgerEntry(Long walletId, LedgerEntryType type, long amount, long balanceAfter,
            String refType, String refId, String idempotencyKey, String reason, String createdBy) {
        this.walletId = walletId;
        this.type = type;
        this.amount = amount;
        this.balanceAfter = balanceAfter;
        this.refType = refType;
        this.refId = refId;
        this.idempotencyKey = idempotencyKey;
        this.reason = reason;
        this.createdBy = createdBy;
    }
}
