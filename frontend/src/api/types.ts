// 백엔드 DTO와 1:1 — 계약이 바뀌면 여기만 고친다

export interface ApiErrorBody {
  code: string
  message: string
  details?: Record<string, unknown>
}

export interface LoginResponse {
  accessToken: string
  expiresInSec: number
}

export interface TypeStat {
  type: string
  count: number
  amount: number
}

export interface Dashboard {
  date: string
  byType: TypeStat[]
  circulating: number
  wallets: number
  unresolvedIssues: number
  draftSettlements: number
}

export interface Balance {
  walletId: number
  userId: number
  balance: number
  expiringSoon: number
}

export interface LedgerEntry {
  id: number
  type: string
  amount: number
  balanceAfter: number
  refType: string | null
  refId: string | null
  relatedEntryId: number | null
  merchantId: number | null
  reason: string | null
  createdBy: string
  createdAt: string
}

export interface LedgerPage {
  items: LedgerEntry[]
  nextCursor: number | null
}

export interface GrantResult {
  entryId: number
  lotId: number
  balanceAfter: number
  expiresAt: string
}

export interface RevokeResult {
  entryId: number
  balanceAfter: number
  consumedLots: { lotId: number; amount: number }[]
}

export interface Issue {
  id: number
  jobRunId: number
  issueType: string
  walletId: number | null
  merchantId: number | null
  refId: string | null
  expected: number | null
  actual: number | null
  resolved: boolean
  memo: string | null
  resolvedBy: string | null
  resolvedAt: string | null
  createdAt: string
}

export interface Merchant {
  id: number
  name: string
  status: string
  createdAt: string
}

export interface Settlement {
  id: number
  merchantId: number
  merchantName: string | null
  settleDate: string
  totalAmount: number
  status: string
  confirmedBy: string | null
  confirmedAt: string | null
}

export interface JobRun {
  executionId: number | null
  jobName: string
  status: string
  exitCode: string
}

export interface Execution {
  executionId: number
  jobName: string
  status: string
  exitCode: string
  startTime: string | null
  endTime: string | null
  parameters: Record<string, string>
}
