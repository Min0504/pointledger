package com.pointledger.settlement;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.LocalDate;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 일일 정산서. (merchant_id, settle_date) UNIQUE가 재실행·이중 기동 멱등성의
 * 근거다 — 배치 코드가 아니라 스키마가 중복 정산서를 거부한다 (기획서 §10-2).
 *
 * DRAFT 동안은 재집계로 갱신될 수 있고(늦게 도착한 원장 반영), CONFIRMED는
 * 동결된다 — 이후의 어긋남은 대사가 SETTLEMENT_MISMATCH로 발견한다.
 */
@Entity
@Table(name = "settlements")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Settlement {

    public enum Status { DRAFT, CONFIRMED }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "merchant_id", nullable = false)
    private Long merchantId;

    @Column(name = "settle_date", nullable = false)
    private LocalDate settleDate;

    @Column(name = "total_amount", nullable = false)
    private long totalAmount;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Status status = Status.DRAFT;

    @Column(name = "confirmed_by", length = 64)
    private String confirmedBy;

    @Column(name = "confirmed_at")
    private Instant confirmedAt;

    @Column(name = "created_at", nullable = false, updatable = false, insertable = false)
    private Instant createdAt;

    /** 확정은 멱등 — 이미 CONFIRMED면 그대로 둔다 (운영 도구는 재클릭에 관대해야) */
    public void confirm(String operator) {
        if (this.status == Status.CONFIRMED) {
            return;
        }
        this.status = Status.CONFIRMED;
        this.confirmedBy = operator;
        this.confirmedAt = Instant.now();
    }
}
