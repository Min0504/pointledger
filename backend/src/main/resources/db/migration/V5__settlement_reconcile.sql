-- V5: 정산·대사 (기획서 §5 merchants/settlements/settlement_lines/reconcile_issues, 문제 5)
--
-- 원칙: "안 틀리게"가 아니라 "틀림을 발견하고 수습하는" 절차가 시스템의 일부다.
-- 정산 멱등성은 락이 아니라 UNIQUE 제약이 지킨다 — 배치가 두 번 돌아도,
-- 두 인스턴스가 동시에 돌아도 중복 정산서는 스키마가 거부한다 (§10-2).

-- ── 가맹점 ─────────────────────────────────────────────────────────
CREATE TABLE merchants (
    id         BIGSERIAL    PRIMARY KEY,
    name       VARCHAR(100) NOT NULL UNIQUE,
    status     VARCHAR(20)  NOT NULL DEFAULT 'ACTIVE'
               CHECK (status IN ('ACTIVE', 'INACTIVE')),
    created_at TIMESTAMPTZ  NOT NULL DEFAULT now()
);

-- 사용(REDEEM)이 어느 가맹점에서 일어났는지 — 정산 집계의 근거.
-- 주문 서버가 아는 사실이므로 요청에서 선택적으로 받는다 (미지정은 정산 제외).
ALTER TABLE ledger_entries ADD COLUMN merchant_id BIGINT REFERENCES merchants (id);
-- 정산 집계용: 가맹점별 + 일자 경계 스캔. merchant 없는 행은 인덱스에서 제외
CREATE INDEX idx_ledger_merchant_created ON ledger_entries (merchant_id, created_at)
    WHERE merchant_id IS NOT NULL;

-- ── 정산서 ─────────────────────────────────────────────────────────
CREATE TABLE settlements (
    id           BIGSERIAL   PRIMARY KEY,
    merchant_id  BIGINT      NOT NULL REFERENCES merchants (id),
    settle_date  DATE        NOT NULL,
    -- 음수 허용: 전일 사용의 취소가 이월되면 차감 정산이 된다 (도메인 규칙)
    total_amount BIGINT      NOT NULL,
    status       VARCHAR(20) NOT NULL DEFAULT 'DRAFT'
                 CHECK (status IN ('DRAFT', 'CONFIRMED')),
    confirmed_by VARCHAR(64),
    confirmed_at TIMESTAMPTZ,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    -- 재실행·이중 기동 멱등성의 근거 — 같은 (가맹점, 날짜) 정산서는 하나뿐
    CONSTRAINT uq_settlement_merchant_date UNIQUE (merchant_id, settle_date)
);

CREATE TABLE settlement_lines (
    id              BIGSERIAL PRIMARY KEY,
    settlement_id   BIGINT    NOT NULL REFERENCES settlements (id),
    -- 한 거래가 두 정산서에 들어가는 사고를 스키마가 차단
    ledger_entry_id BIGINT    NOT NULL UNIQUE REFERENCES ledger_entries (id),
    -- 부호 있는 금액: REDEEM +, CANCEL − — 라인 합 == 정산서 총액 대사용
    amount          BIGINT    NOT NULL
);

CREATE INDEX idx_settlement_lines_settlement ON settlement_lines (settlement_id);

-- ── 대사 불일치 기록 ─────────────────────────────────────────────────
-- 자동 수정 금지: 탐지는 자동, 수정은 사람 (ADMIN_GRANT/REVOKE + 사유로만 수습)
CREATE TABLE reconcile_issues (
    id          BIGSERIAL    PRIMARY KEY,
    job_run_id  BIGINT       NOT NULL,               -- 어느 대사 실행이 발견했나
    issue_type  VARCHAR(30)  NOT NULL
                CHECK (issue_type IN ('SNAPSHOT_MISMATCH', 'EXTERNAL_MISSING',
                                      'INTERNAL_MISSING', 'SETTLEMENT_MISMATCH')),
    wallet_id   BIGINT,                              -- SNAPSHOT_MISMATCH
    merchant_id BIGINT,                              -- SETTLEMENT_MISMATCH
    ref_id      VARCHAR(64),                         -- EXTERNAL/INTERNAL_MISSING
    expected    BIGINT,                              -- 원장(진실) 기준 값
    actual      BIGINT,                              -- 대조 대상의 값 (누락이면 NULL)
    resolved    BOOLEAN      NOT NULL DEFAULT false,
    memo        VARCHAR(500),                        -- 처리 근거 — 해결 시 필수
    resolved_by VARCHAR(64),
    resolved_at TIMESTAMPTZ,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX idx_issues_unresolved ON reconcile_issues (resolved, created_at DESC);

-- ── 외부(주문 서버 mock) 기록 스테이징 ──────────────────────────────
-- 대사 2단의 입력. CSV를 적재한 뒤 SQL 안티 조인으로 양방향 누락을 찾는다 —
-- 파일을 행 단위로 비교하는 것보다 집합 연산이 정확하고 빠르다.
CREATE TABLE external_order_records (
    id          BIGSERIAL   PRIMARY KEY,
    record_date DATE        NOT NULL,
    ref_id      VARCHAR(64) NOT NULL,
    entry_type  VARCHAR(20) NOT NULL CHECK (entry_type IN ('EARN', 'REDEEM')),
    user_id     BIGINT      NOT NULL,
    amount      BIGINT      NOT NULL CHECK (amount > 0)
);

CREATE INDEX idx_external_records_date ON external_order_records (record_date);
