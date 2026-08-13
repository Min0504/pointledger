-- V1: 지갑·원장·로트 + 인증(운영자/API 키)
--
-- 설계 원칙 (기획서 §5):
--   1. 원장(ledger_entries)이 진실이고 잔액(wallets.balance)은 파생값이다.
--   2. 원장은 append-only — 정정은 반대 방향 엔트리 추가로만 (회계의 붉은 줄 원리).
--   3. 도메인 규칙 중 스키마로 강제할 수 있는 것은 전부 스키마로 강제한다
--      (CHECK·UNIQUE·부분 인덱스) — 코드 버그가 뚫려도 DB가 막는 최후 방어선.

-- ── 지갑: 잔액 스냅샷 ─────────────────────────────────────────────
CREATE TABLE wallets (
    id         BIGSERIAL PRIMARY KEY,
    user_id    BIGINT      NOT NULL UNIQUE,             -- 사용자당 1지갑
    balance    BIGINT      NOT NULL DEFAULT 0
               CHECK (balance >= 0),                    -- 음수 잔액 최후 방어선
    version    INT         NOT NULL DEFAULT 0,          -- 낙관적 락 실험용 (@Version)
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- ── 원장: 이 프로젝트의 심장. append-only ──────────────────────────
CREATE TABLE ledger_entries (
    id              BIGSERIAL PRIMARY KEY,
    wallet_id       BIGINT      NOT NULL REFERENCES wallets (id),
    type            VARCHAR(20) NOT NULL
                    CHECK (type IN ('EARN', 'REDEEM', 'CANCEL', 'EXPIRE',
                                    'ADMIN_GRANT', 'ADMIN_REVOKE')),
    -- 금액은 항상 양수. 방향(+/-)은 type이 결정한다 — 부호 실수를 원천 차단 (기획서 §5)
    amount          BIGINT      NOT NULL CHECK (amount > 0),
    -- 기록 시점 잔액 스냅샷 — 불일치 발생 시 이력에서 지점을 이분탐색할 수 있게 한다
    balance_after   BIGINT      NOT NULL CHECK (balance_after >= 0),
    ref_type        VARCHAR(20),                        -- 출처 도메인 (ORDER/PROMO/CS …)
    ref_id          VARCHAR(64),                        -- 출처 식별자 (주문번호 등)
    idempotency_key VARCHAR(64),                        -- 멱등 처리의 2차 방어 (1차는 V2에서)
    -- 운영자 수동 지급/회수는 사유 없이는 기록될 수 없다 — 운영 규칙을 스키마로 강제
    reason          VARCHAR(200)
                    CHECK (type NOT IN ('ADMIN_GRANT', 'ADMIN_REVOKE') OR reason IS NOT NULL),
    created_by      VARCHAR(64) NOT NULL,               -- 감사: 시스템(API 키 이름)/운영자 이메일
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- 사용자별 이력 커서 조회용 (기획서 §8)
CREATE INDEX idx_ledger_wallet_id_desc ON ledger_entries (wallet_id, id DESC);
-- 외부 기록(주문 서버)과의 대사용
CREATE INDEX idx_ledger_ref ON ledger_entries (ref_type, ref_id);
-- 같은 멱등 키로 원장이 두 번 기록되는 것을 DB가 차단 (NULL 허용을 위한 부분 인덱스)
CREATE UNIQUE INDEX uq_ledger_idempotency_key ON ledger_entries (idempotency_key)
    WHERE idempotency_key IS NOT NULL;

-- append-only 강제: UPDATE/DELETE를 DB 레벨에서 차단한다.
-- 기획서 §9의 "앱 계정에 UPDATE 권한 미부여"와 같은 목표를 트리거로 구현했다 —
-- 로컬/Testcontainers처럼 앱 계정이 곧 소유자인 환경에서도 불변성이 유지되고,
-- 운영에서는 계정 권한 분리(REVOKE UPDATE, DELETE)를 겹쳐 이중화할 수 있다.
CREATE FUNCTION reject_ledger_mutation() RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION 'ledger_entries is append-only: % not allowed', TG_OP;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_ledger_append_only
    BEFORE UPDATE OR DELETE ON ledger_entries
    FOR EACH ROW EXECUTE FUNCTION reject_ledger_mutation();

-- ── 적립 로트: FIFO 사용·만료의 기반 ────────────────────────────────
CREATE TABLE point_lots (
    id             BIGSERIAL PRIMARY KEY,
    wallet_id      BIGINT      NOT NULL REFERENCES wallets (id),
    earn_entry_id  BIGINT      NOT NULL UNIQUE REFERENCES ledger_entries (id), -- 적립 1건 = 로트 1개
    initial_amount BIGINT      NOT NULL CHECK (initial_amount > 0),
    remaining      BIGINT      NOT NULL
                   CHECK (remaining >= 0 AND remaining <= initial_amount),
    expires_at     TIMESTAMPTZ NOT NULL,
    status         VARCHAR(20) NOT NULL DEFAULT 'ACTIVE'
                   CHECK (status IN ('ACTIVE', 'EXHAUSTED', 'EXPIRED')),
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- 만료 배치 스캔용 부분 인덱스 — 잔여가 있는 로트만 대상이므로 인덱스도 그만큼만 유지
CREATE INDEX idx_lots_expires_at_remaining ON point_lots (expires_at) WHERE remaining > 0;
-- FIFO 차감용: 지갑별 만료 임박 순
CREATE INDEX idx_lots_wallet_fifo ON point_lots (wallet_id, expires_at, id) WHERE remaining > 0;

-- ── 인증: 운영자(백오피스 JWT) / 서버 간 API 키 ─────────────────────
CREATE TABLE operators (
    id            BIGSERIAL PRIMARY KEY,
    email         VARCHAR(255) NOT NULL UNIQUE,
    password_hash VARCHAR(72)  NOT NULL,                -- bcrypt
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE TABLE api_keys (
    id           BIGSERIAL PRIMARY KEY,
    name         VARCHAR(64)  NOT NULL UNIQUE,          -- 호출 주체 식별 (감사 created_by로 기록)
    key_hash     CHAR(64)     NOT NULL UNIQUE,          -- 원문 미저장 — SHA-256 (기획서 §9)
    active       BOOLEAN      NOT NULL DEFAULT true,
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    last_used_at TIMESTAMPTZ
);
