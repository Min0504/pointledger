package com.pointledger.ledger;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 소비 기록 — "이 사용(REDEEM/ADMIN_REVOKE)이 어느 로트에서 얼마나 나갔나".
 * 취소 시 원래 로트로의 복원과 정확한 만료 계산의 근거다 (기획서 §5).
 *
 * 원장과 달리 restored는 갱신된다: 감사 기록은 CANCEL 원장 엔트리가 담당하고,
 * restored는 "이 소비 중 얼마가 이미 복원됐나"를 나타내는 파생 상태다 —
 * 같은 소비를 두 번 되돌리는 것은 스키마 CHECK(restored <= amount)가 차단한다.
 */
@Entity
@Table(name = "lot_consumptions")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class LotConsumption {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "consuming_entry_id", nullable = false)
    private Long consumingEntryId;

    @Column(name = "lot_id", nullable = false)
    private Long lotId;

    @Column(nullable = false)
    private long amount;

    @Column(nullable = false)
    private long restored;

    @Column(name = "created_at", nullable = false, updatable = false, insertable = false)
    private Instant createdAt;

    public LotConsumption(Long consumingEntryId, Long lotId, long amount) {
        this.consumingEntryId = consumingEntryId;
        this.lotId = lotId;
        this.amount = amount;
        this.restored = 0;
    }

    /** 이 소비에서 아직 되돌릴 수 있는 몫 */
    public long restorable() {
        return amount - restored;
    }

    public void markRestored(long value) {
        if (value <= 0 || value > restorable()) {
            throw new IllegalArgumentException(
                    "복원 초과: restorable=" + restorable() + ", requested=" + value);
        }
        this.restored += value;
    }
}
