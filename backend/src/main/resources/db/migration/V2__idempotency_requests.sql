-- V2: 멱등 요청 상태 테이블 (기획서 §5, 문제 2)
--
-- 키 유무만 저장하는 단순 구현과의 차이: 상태(IN_PROGRESS/DONE)와 응답 보관이
-- 있어야 "처리 중 재시도"(409로 잠시 후 재시도 유도)와 "완료 후 재시도"(저장된
-- 응답 그대로 재생)를 다르게 답할 수 있다. Stripe·Toss Payments 공개 API가
-- 이 패턴의 실제 사례다.
CREATE TABLE idempotency_requests (
    idem_key        VARCHAR(64)  PRIMARY KEY,          -- 호출자 도메인 키 (주문번호 기반 허용)
    -- 같은 키 + 다른 바디 = 키 재사용 실수 → 422로 조기에 드러낸다
    request_hash    CHAR(64)     NOT NULL,
    status          VARCHAR(20)  NOT NULL DEFAULT 'IN_PROGRESS'
                    CHECK (status IN ('IN_PROGRESS', 'DONE')),
    -- 재생용 최종 응답 (HTTP 상태 + 바디). 도메인 에러(409 잔액 부족 등)도 저장한다 —
    -- 같은 키는 언제나 같은 답을 받아야 하므로. 5xx는 저장하지 않는다(진짜 재시도 허용)
    response_status INT,
    response_body   JSONB,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    -- 보관 7일 — 응답 테이블이 무한히 자라지 않도록 정리 배치의 대상 (Phase 5)
    expires_at      TIMESTAMPTZ  NOT NULL DEFAULT now() + interval '7 days'
);

CREATE INDEX idx_idem_expires_at ON idempotency_requests (expires_at);
