# 배치 설계 — 만료·정산·대사

> 상세 근거와 테스트: [PR #5 만료](https://github.com/Min0504/pointledger/pull/5) · [PR #6 정산·대사](https://github.com/Min0504/pointledger/pull/6)

## 0. 공통 골격

| 관심사 | 선택 | 근거 |
|--------|------|------|
| 실행 기록 | Spring Batch `JobRepository` (BATCH_* 테이블) | 재시작 지점·파라미터·종료 코드가 DB에 남는다 — 운영 화면이 이 메타데이터를 그대로 조회 |
| 이중 기동 방어 | ShedLock (JDBC) | 서버 2대가 같은 cron을 울려도 한쪽만 실행. Redis 없이 기존 DB로 해결 |
| 스케줄 | `@Scheduled` cron — 만료 04시 · 정산 05시 · 대사 06시 (KST) | 만료가 확정된 뒤 정산, 정산까지 끝난 뒤 대사 — 순서가 곧 의존성 |
| 수동 소급 실행 | `POST /admin/batch/{job}/run` (날짜 파라미터) | 스케줄을 놓친 날짜의 재실행 경로를 처음부터 운영 API로 |
| 재실행 정책 | 동일 날짜 재실행 허용 — `triggeredAt`을 식별 파라미터에 포함 | "완료된 JobInstance는 재실행 불가"라는 Batch 규칙과 "늦은 데이터 재정산" 요구의 절충 |

## 1. 만료 Job — 수십만 로트를 죽지 않고, 두 번 죽이지도 않고

```text
expireDailyJob
└─ expireStep (청크 500)
   ├─ Reader   JdbcPagingItemReader — keyset(id) 페이징
   │           WHERE expires_at < :today AND remaining > 0
   ├─ Processor 없음 (판정은 SQL이 이미 했다)
   └─ Writer   LedgerService.expireLots(walletId, lotIds)
               └─ 지갑 락 → 로트 재확인 → EXPIRE 원장 INSERT → 잔액 갱신
```

핵심 결정 세 가지:

1. **Reader는 offset이 아니라 keyset.** `OFFSET 200000`은 20만 행을 세고 버린다.
   `WHERE id > :lastId ORDER BY id LIMIT 500`은 부분 인덱스(`expires_at WHERE remaining > 0`)를 타고 일정 속도를 유지한다.
2. **Writer가 온라인과 같은 락 규율을 따른다.** 배치라고 지갑 락을 우회하면, 새벽에 사용 중인 지갑에서
   "만료됐는데 사용됨"이 생긴다. Writer는 지갑 락 안에서 `remaining`을 **다시 읽고** 지금 남은 만큼만 만료한다 —
   Reader가 본 값은 힌트일 뿐, 진실은 락 안에서 확정한다.
3. **멱등성은 세 겹.**
   - Job 레벨: 같은 파라미터의 완료된 JobInstance는 Batch가 재실행을 거부
   - 재시작 레벨: 실패 시 완료 청크는 스킵하고 실패 지점부터 (JobRepository 체크포인트)
   - 데이터 레벨: `remaining > 0` 조건 + 락 안 재확인 — 어떤 경로로 두 번 들어와도 이중 만료 불가

검증: 3번째 청크에서 강제 예외 → FAILED → 재기동 → 완료 청크 스킵 확인, EXPIRE 원장 중복 0건.
ShedLock 동시 기동 테스트에서 한쪽만 실행됨을 확인.

## 2. 정산 Job — 멱등성을 코드가 아니라 스키마에 둔다

```text
settleDailyJob (tasklet, 날짜 파라미터)
  1. DELETE 대상일의 DRAFT 정산 (CONFIRMED는 건드리지 않음)
  2. INSERT settlements  — 가맹점별 REDEEM 합계 - CANCEL 합계 (집계 SQL 한 방)
  3. INSERT settlement_lines — 근거가 된 원장 행 링크
```

- **재실행 = DRAFT 재계산.** 늦게 도착한 취소가 있으면 DRAFT 금액이 갱신된다.
  운영자가 `confirm`한 정산서는 DELETE 대상에서 제외 — **확정은 동결이다.**
  확정 후 어긋남은 다음 날 대사(3단계)가 발견해 이슈로 만든다.
- `UNIQUE (merchant_id, settle_date)`가 어떤 코드 버그에서도 정산서 중복을 막는 최후 방어선.
- 청크 대신 tasklet + 집계 SQL을 쓴 이유: 정산은 "행 단위 변환"이 아니라 "집합 연산"이다.
  DB가 잘하는 일을 애플리케이션 루프로 다시 쓰지 않는다.

## 3. 대사 Job — 자동 수정 금지, 발견만 한다

```text
reconcileDailyJob (날짜 · 선택적 외부 CSV 파라미터)
  0. (CSV 있으면) external_order_records 적재 — FlatFileItemReader
  1. 스냅샷 대사    SUM(ledger) vs wallets.balance         → SNAPSHOT_MISMATCH
  2. 외부 대사      원장 EARN/REDEEM vs 외부 주문 기록      → EXTERNAL_MISSING / INTERNAL_MISSING / AMOUNT_MISMATCH
  3. 정산 대사      settlements.total vs 원장 재집계        → SETTLEMENT_DRIFT
```

- **발견한 불일치는 `reconcile_issues`에 적재할 뿐 잔액을 고치지 않는다.**
  자동 정정은 "왜 틀렸는지"를 지운다. 정정은 운영자가 사유와 함께 ADMIN_GRANT/REVOKE로 — 그 정정도 원장에 남는다.
  ([운영 runbook](operations.md)의 정정 절차 참고)
- 같은 (유형, 대상)의 **미해결** 이슈가 있으면 중복 적재하지 않는다 — 매일 돌아도 이슈가 불어나지 않는다.
  해결된 이슈는 대상에서 빠지므로, 원인을 안 고치고 이슈만 닫으면 다음 대사가 다시 잡아낸다.

## 4. 실행 이력과 운영 화면

`GET /admin/batch/executions`가 JobExplorer로 BATCH_JOB_EXECUTION을 조회해
어드민 "배치" 탭에 Job·상태·파라미터·시작/종료 시각을 노출한다.
별도 실행 기록 테이블을 만들지 않은 이유: **Spring Batch 메타데이터가 이미 그 테이블이다.**

## 5. 스케줄러 인프라의 발전 경로 (학습 노트)

현재는 단일 인스턴스 `@Scheduled` + ShedLock으로 충분하다. 규모가 커지면:

```text
@Scheduled + ShedLock          →  cron/EventBridge가 컨테이너 기동      →  Airflow 등 오케스트레이터
(지금: 서버가 곧 스케줄러)          (배치를 API 서버에서 분리,               (Job 간 의존성 그래프·재시도 정책·
 배포 단순, 서버 수명에 종속         같은 이미지를 --spring.batch.job.name    백필을 선언적으로 관리)
                                   인자로 실행)
```

이관 시에도 Job 코드는 그대로다 — JobParameters 기반 실행 계약이 스케줄러와 Job을 분리해 두었기 때문.
