import { useState, type FormEvent } from 'react'
import { ApiError, api } from '../api/client'
import type { Balance, LedgerEntry } from '../api/types'
import { Badge, Button, Card, Empty, ErrorNote, StatCard, Table, inputClass } from '../components/ui'
import { formatDateTime, formatPoints, isCredit, typeLabel } from '../lib/format'

/** 지갑 검색 → 원장 타임라인 — 엔트리별 잔액 변화(balanceAfter)를 그대로 보여준다 */
export default function WalletPage() {
  const [userIdInput, setUserIdInput] = useState('')
  const [balance, setBalance] = useState<Balance | null>(null)
  const [entries, setEntries] = useState<LedgerEntry[]>([])
  const [nextCursor, setNextCursor] = useState<number | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)

  async function search(e: FormEvent) {
    e.preventDefault()
    const userId = Number(userIdInput)
    if (!Number.isInteger(userId) || userId <= 0) return
    setBusy(true)
    setError(null)
    try {
      const [bal, page] = await Promise.all([api.balance(userId), api.ledger(userId, null)])
      setBalance(bal)
      setEntries(page.items)
      setNextCursor(page.nextCursor)
    } catch (err) {
      setBalance(null)
      setEntries([])
      setNextCursor(null)
      setError(err instanceof ApiError && err.code === 'WALLET_NOT_FOUND'
        ? `사용자 ${userId}의 지갑이 없습니다.` : (err as Error).message)
    } finally {
      setBusy(false)
    }
  }

  async function loadMore() {
    if (!balance || nextCursor === null) return
    const page = await api.ledger(balance.userId, nextCursor)
    setEntries((prev) => [...prev, ...page.items])
    setNextCursor(page.nextCursor)
  }

  return (
    <div className="space-y-6">
      <Card title="지갑 검색">
        <form onSubmit={search} className="flex gap-2">
          <input
            className={`${inputClass} max-w-xs`}
            placeholder="사용자 ID (예: 42)"
            value={userIdInput}
            onChange={(e) => setUserIdInput(e.target.value)}
            inputMode="numeric"
          />
          <Button type="submit" disabled={busy}>{busy ? '조회 중…' : '조회'}</Button>
        </form>
        <div className="mt-3">
          <ErrorNote message={error} />
        </div>
      </Card>

      {balance && (
        <>
          <div className="grid grid-cols-2 gap-4 lg:grid-cols-3">
            <StatCard label={`사용자 ${balance.userId} · 지갑 ${balance.walletId}`} value={formatPoints(balance.balance)} sub="현재 잔액 (스냅샷)" />
            <StatCard label="30일 내 만료 예정" value={formatPoints(balance.expiringSoon)} tone={balance.expiringSoon > 0 ? 'warn' : 'default'} />
          </div>

          <Card title="원장 타임라인 (최신순)">
            {entries.length === 0 ? (
              <Empty text="원장 기록이 없습니다." />
            ) : (
              <>
                <Table head={['#', '유형', '금액', '기록 후 잔액', '참조', '사유', '주체', '시각']}>
                  {entries.map((e) => (
                    <tr key={e.id}>
                      <td className="px-3 py-2 tabular-nums text-slate-400">{e.id}</td>
                      <td className="px-3 py-2">
                        <Badge tone={isCredit(e.type) ? 'green' : e.type === 'EXPIRE' ? 'slate' : 'red'}>
                          {typeLabel(e.type)}
                        </Badge>
                        {e.relatedEntryId !== null && (
                          <span className="ml-1 text-xs text-slate-400">↩ #{e.relatedEntryId}</span>
                        )}
                      </td>
                      <td className={`px-3 py-2 tabular-nums font-medium ${isCredit(e.type) ? 'text-emerald-600' : 'text-rose-600'}`}>
                        {isCredit(e.type) ? '+' : '−'}{formatPoints(e.amount)}
                      </td>
                      <td className="px-3 py-2 tabular-nums">{formatPoints(e.balanceAfter)}</td>
                      <td className="px-3 py-2 text-xs text-slate-500">
                        {e.refType ? (e.refId ? `${e.refType}:${e.refId}` : e.refType) : '—'}
                      </td>
                      <td className="px-3 py-2 text-xs text-slate-500">{e.reason ?? '—'}</td>
                      <td className="px-3 py-2 text-xs text-slate-500">{e.createdBy}</td>
                      <td className="px-3 py-2 text-xs tabular-nums text-slate-500">{formatDateTime(e.createdAt)}</td>
                    </tr>
                  ))}
                </Table>
                {nextCursor !== null && (
                  <div className="mt-4 text-center">
                    <Button variant="ghost" onClick={loadMore}>더 보기</Button>
                  </div>
                )}
              </>
            )}
          </Card>
        </>
      )}
    </div>
  )
}
