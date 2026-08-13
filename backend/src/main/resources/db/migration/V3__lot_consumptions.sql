-- V3: 로트 소비 기록 + 취소 연결 (기획서 §5 lot_consumptions, Phase 4)
--
-- "3,000P 사용이 로트 A(2,000)·B(1,000)에서 나갔다"는 사실이 없으면
-- ① 취소 시 원래 로트로 복원할 수 없고 ② 만료 계산이 부정확해진다 —
-- 사용↔로트 N:M을 해소하는 테이블. 기획서 스케치의 redeem_entry_id 대신
-- consuming_entry_id로 명명한다: 로트를 소비하는 주체가 REDEEM만이 아니라
-- ADMIN_REVOKE도 있기 때문 (둘 다 잔액을 줄이므로 로트도 함께 줄어야
-- SUM(lot.remaining) == balance 불변식이 유지된다).
CREATE TABLE lot_consumptions (
    id                 BIGSERIAL   PRIMARY KEY,
    consuming_entry_id BIGINT      NOT NULL REFERENCES ledger_entries (id),
    lot_id             BIGINT      NOT NULL REFERENCES point_lots (id),
    amount             BIGINT      NOT NULL CHECK (amount > 0),
    -- 부분 취소 누적분 — 같은 소비를 두 번 복원하는 것을 스키마가 차단한다.
    -- 이 테이블은 원장과 달리 restored가 갱신된다: 감사 기록은 CANCEL 원장이
    -- 담당하고, restored는 "얼마나 되돌렸나"의 파생 상태이기 때문.
    restored           BIGINT      NOT NULL DEFAULT 0
                       CHECK (restored >= 0 AND restored <= amount),
    created_at         TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- 취소 시 "이 사용이 어느 로트에서 나갔나" 역추적용
CREATE INDEX idx_consumptions_entry ON lot_consumptions (consuming_entry_id);
-- 감사·만료 검증 시 "이 로트가 어디에 쓰였나" 추적용
CREATE INDEX idx_consumptions_lot ON lot_consumptions (lot_id);

-- CANCEL 원장이 어느 REDEEM을 되돌리는지 — 외부 출처(ref_type/ref_id)와
-- 내부 감사 연결을 분리한다. ref_*는 주문 서버와의 대사용으로 남겨둔다.
ALTER TABLE ledger_entries ADD COLUMN related_entry_id BIGINT REFERENCES ledger_entries (id);
ALTER TABLE ledger_entries ADD CONSTRAINT chk_cancel_requires_related
    CHECK (type <> 'CANCEL' OR related_entry_id IS NOT NULL);
