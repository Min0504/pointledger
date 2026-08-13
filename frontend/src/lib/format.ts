export function formatPoints(n: number): string {
  return `${n.toLocaleString('ko-KR')}P`
}

export function formatDateTime(iso: string | null): string {
  if (!iso) return '—'
  return new Date(iso).toLocaleString('ko-KR', {
    year: '2-digit',
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
    second: '2-digit',
  })
}

const TYPE_LABELS: Record<string, string> = {
  EARN: '적립',
  REDEEM: '사용',
  CANCEL: '사용취소',
  EXPIRE: '만료',
  ADMIN_GRANT: '수동지급',
  ADMIN_REVOKE: '수동회수',
}

export function typeLabel(type: string): string {
  return TYPE_LABELS[type] ?? type
}

/** 잔액을 늘리는 유형인가 — 타임라인의 +/− 색상 결정 */
export function isCredit(type: string): boolean {
  return type === 'EARN' || type === 'CANCEL' || type === 'ADMIN_GRANT'
}

const ISSUE_LABELS: Record<string, string> = {
  SNAPSHOT_MISMATCH: '지갑 스냅샷 불일치',
  EXTERNAL_MISSING: '외부 기록에 없음',
  INTERNAL_MISSING: '내부 원장에 없음',
  SETTLEMENT_MISMATCH: '정산서 불일치',
}

export function issueLabel(type: string): string {
  return ISSUE_LABELS[type] ?? type
}
