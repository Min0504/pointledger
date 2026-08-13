# PointLedger 웹 어드민

운영자 백오피스 SPA. 원장 타임라인·대사 이슈 처리·정산 확정·배치 소급 실행을 화면으로 제공한다.
프론트는 시연용이며, 설계의 본체는 [backend](../backend)와 [docs](../docs)에 있다.

## 스택·실행

React 19 + TypeScript(strict) + Vite + Tailwind CSS 4 — 상태 라이브러리 없이 컨텍스트(인증)와 로컬 상태로 충분한 규모.

```bash
npm install
npm run dev      # :5173 — /api를 백엔드(:8081)로 프록시
npm run build    # tsc -b && vite build
npm run lint     # oxlint
```

로그인: `admin@pointledger.io` / 백엔드 `PL_ADMIN_PASSWORD` 값.

## 화면

| 경로 | 화면 | 백엔드 계약에서 중요한 점 |
|------|------|--------------------------|
| `/` | 대시보드 — 오늘 원장 흐름·유통 잔액·미해결 이슈 | `GET /admin/dashboard` 집계 |
| `/wallets` | 지갑 검색 + 원장 타임라인 | 커서 페이징, CANCEL 행은 원 REDEEM 링크 표시 |
| `/points` | 수동 지급·회수 | 사유 필수 + 확인 모달, 제출 시 `Idempotency-Key` 헤더 생성 |
| `/issues` | 대사 이슈 목록·해결 처리 | 해결은 검토 표시일 뿐 — 잔액 정정은 지급·회수로 |
| `/settlements` | 정산서 조회·확정 | 확정은 동결 — 이후 어긋남은 대사가 발견 |
| `/batch` | 배치 소급 실행 + 실행 이력 | Spring Batch JobRepository 메타데이터 그대로 표시 |

스크린샷은 [루트 README](../README.md#웹-어드민-백오피스) 참고.
