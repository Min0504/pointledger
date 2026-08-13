# PointLedger — 포인트 월렛·정산 시스템

> 적립·사용·만료·정산까지, 돈처럼 다뤄야 하는 포인트를 **append-only 원장**으로 관리하는 백오피스 시스템.
> "잔액이 절대 틀리지 않는 시스템"을 목표로 트랜잭션 격리수준·멱등성·Spring Batch·대사(reconciliation)를 정면으로 다룹니다.
> 원장이 진실이고 잔액은 파생값이며, 모든 포인트 변화는 반드시 원장 INSERT를 동반합니다.

구현 진행 중입니다. 상세 계획은 [docs/기획서.md](docs/기획서.md)를 참고하세요.

## 저장소 구조

```text
pointledger/
├── backend/    # Spring Boot 3 + Spring Batch — 학습의 본체
├── frontend/   # 웹 어드민(백오피스) — 시연용
└── docs/       # 기획서 · ERD · 격리수준 실험 기록 · incident 리포트
```

## 개발

```bash
docker compose -f docker-compose.dev.yml up -d   # 개발용 PostgreSQL (:55433)
cd backend && ./gradlew test                     # 통합 테스트 (Testcontainers 자체 기동)
cd backend && JWT_SECRET=dev-jwt-secret-32bytes-minimum!! PL_ADMIN_PASSWORD=password1234 ./gradlew bootRun
```

Swagger: <http://localhost:8081/docs>
