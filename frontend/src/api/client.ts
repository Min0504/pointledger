import type {
  ApiErrorBody,
  Balance,
  Dashboard,
  Execution,
  GrantResult,
  Issue,
  JobRun,
  LedgerPage,
  LoginResponse,
  Merchant,
  RevokeResult,
  Settlement,
} from './types'

export class ApiError extends Error {
  readonly status: number
  readonly code: string
  readonly details?: Record<string, unknown>

  constructor(status: number, code: string, message: string, details?: Record<string, unknown>) {
    super(message)
    this.status = status
    this.code = code
    this.details = details
  }
}

const TOKEN_KEY = 'pointledger.token'
const EMAIL_KEY = 'pointledger.email'

export function loadToken(): string | null {
  return localStorage.getItem(TOKEN_KEY)
}

export function loadEmail(): string | null {
  return localStorage.getItem(EMAIL_KEY)
}

export function saveSession(token: string, email: string): void {
  localStorage.setItem(TOKEN_KEY, token)
  localStorage.setItem(EMAIL_KEY, email)
}

export function clearSession(): void {
  localStorage.removeItem(TOKEN_KEY)
  localStorage.removeItem(EMAIL_KEY)
}

async function parseError(res: Response): Promise<ApiError> {
  const body = (await res.json().catch(() => null)) as ApiErrorBody | null
  return new ApiError(
    res.status,
    body?.code ?? 'UNKNOWN',
    body?.message ?? `HTTP ${res.status}`,
    body?.details,
  )
}

interface RequestOptions {
  method?: string
  body?: unknown
  headers?: Record<string, string>
}

async function request<T>(path: string, opts: RequestOptions = {}): Promise<T> {
  const token = loadToken()
  const res = await fetch(`/api${path}`, {
    method: opts.method ?? 'GET',
    headers: {
      ...(opts.body !== undefined && { 'Content-Type': 'application/json' }),
      ...(token && { Authorization: `Bearer ${token}` }),
      ...opts.headers,
    },
    ...(opts.body !== undefined && { body: JSON.stringify(opts.body) }),
  })

  // 내부 백오피스라 refresh 없음 — 세션(1h)이 끝나면 재로그인이 가장 단순하다
  if (res.status === 401) {
    clearSession()
    if (!location.pathname.startsWith('/login')) location.assign('/login')
    throw await parseError(res)
  }
  if (!res.ok) throw await parseError(res)
  return (await res.json()) as T
}

export const api = {
  login: (email: string, password: string) =>
    request<LoginResponse>('/auth/login', { method: 'POST', body: { email, password } }),

  dashboard: () => request<Dashboard>('/admin/dashboard'),

  balance: (userId: number) => request<Balance>(`/wallets/${userId}/balance`),

  ledger: (userId: number, cursor: number | null) =>
    request<LedgerPage>(
      `/wallets/${userId}/ledger?size=20${cursor !== null ? `&cursor=${cursor}` : ''}`,
    ),

  // 수동 지급/회수는 멱등키 필수 — 더블클릭·재시도가 두 번 기장되지 않는다
  grant: (body: unknown, idempotencyKey: string) =>
    request<GrantResult>('/admin/points/grant', {
      method: 'POST',
      body,
      headers: { 'Idempotency-Key': idempotencyKey },
    }),

  revoke: (body: unknown, idempotencyKey: string) =>
    request<RevokeResult>('/admin/points/revoke', {
      method: 'POST',
      body,
      headers: { 'Idempotency-Key': idempotencyKey },
    }),

  issues: (resolved: boolean) => request<{ items: Issue[] }>(`/admin/reconcile/issues?resolved=${resolved}`),

  resolveIssue: (id: number, memo: string) =>
    request<Issue>(`/admin/reconcile/issues/${id}/resolve`, { method: 'POST', body: { memo } }),

  merchants: () => request<Merchant[]>('/admin/merchants'),

  settlements: (settleDate: string | null, merchantId: number | null) => {
    const params = new URLSearchParams()
    if (settleDate) params.set('settleDate', settleDate)
    if (merchantId !== null) params.set('merchantId', String(merchantId))
    const qs = params.toString()
    return request<{ items: Settlement[] }>(`/admin/settlements${qs ? `?${qs}` : ''}`)
  },

  confirmSettlement: (id: number) =>
    request<Settlement>(`/admin/settlements/${id}/confirm`, { method: 'POST', body: {} }),

  executions: () => request<Execution[]>('/admin/batch/executions?limit=30'),

  runBatch: (job: 'expire' | 'settle' | 'reconcile', body: unknown) =>
    request<JobRun>(`/admin/batch/${job}/run`, { method: 'POST', body }),
}
