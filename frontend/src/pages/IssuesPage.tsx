import { useCallback, useEffect, useState } from 'react'
import { ApiError, api } from '../api/client'
import type { Issue } from '../api/types'
import { Badge, Button, Card, ConfirmModal, Empty, ErrorNote, Table, inputClass } from '../components/ui'
import { formatDateTime, issueLabel } from '../lib/format'

/** 대사 이슈 — 해결은 검토 표시일 뿐, 실제 정정은 지급·회수 화면에서 사유와 함께 */
export default function IssuesPage() {
  const [tab, setTab] = useState<'open' | 'closed'>('open')
  const [issues, setIssues] = useState<Issue[]>([])
  const [error, setError] = useState<string | null>(null)
  const [target, setTarget] = useState<Issue | null>(null)
  const [memo, setMemo] = useState('')

  const load = useCallback(() => {
    api.issues(tab === 'closed')
      .then((res) => setIssues(res.items))
      .catch((e: Error) => setError(e.message))
  }, [tab])

  useEffect(load, [load])

  async function resolve() {
    if (!target || memo.trim().length === 0) return
    try {
      await api.resolveIssue(target.id, memo.trim())
      setTarget(null)
      setMemo('')
      load()
    } catch (err) {
      setTarget(null)
      setError(err instanceof ApiError ? `${err.code}: ${err.message}` : (err as Error).message)
    }
  }

  return (
    <div className="space-y-4">
      <div className="flex gap-1">
        {(['open', 'closed'] as const).map((t) => (
          <button
            key={t}
            onClick={() => setTab(t)}
            className={`rounded-lg px-3 py-1.5 text-sm font-medium ${
              tab === t ? 'bg-slate-900 text-white' : 'text-slate-500 hover:bg-slate-100'
            }`}
          >
            {t === 'open' ? '미해결' : '처리 완료'}
          </button>
        ))}
      </div>
      <ErrorNote message={error} />

      <Card title={tab === 'open' ? '미해결 이슈 — 자동 수정은 없습니다. 확인 후 근거를 남겨 닫으세요' : '처리 완료 이슈'}>
        {issues.length === 0 ? (
          <Empty text={tab === 'open' ? '미해결 이슈가 없습니다. 오늘의 대사는 깨끗합니다.' : '처리된 이슈가 없습니다.'} />
        ) : (
          <Table head={['#', '유형', '대상', '기대값 (원장 기준)', '실제값', '발견', tab === 'open' ? '처리' : '처리 기록']}>
            {issues.map((i) => (
              <tr key={i.id}>
                <td className="px-3 py-2 tabular-nums text-slate-400">{i.id}</td>
                <td className="px-3 py-2"><Badge tone="amber">{issueLabel(i.issueType)}</Badge></td>
                <td className="px-3 py-2 text-xs text-slate-600">
                  {i.walletId !== null && `지갑 ${i.walletId}`}
                  {i.merchantId !== null && `가맹점 ${i.merchantId}`}
                  {i.refId && <span className="ml-1 text-slate-400">{i.refId}</span>}
                </td>
                <td className="px-3 py-2 tabular-nums">{i.expected?.toLocaleString() ?? '—'}</td>
                <td className="px-3 py-2 tabular-nums">{i.actual?.toLocaleString() ?? '—'}</td>
                <td className="px-3 py-2 text-xs tabular-nums text-slate-500">{formatDateTime(i.createdAt)}</td>
                <td className="px-3 py-2">
                  {tab === 'open' ? (
                    <Button variant="ghost" onClick={() => setTarget(i)}>해결 처리</Button>
                  ) : (
                    <span className="text-xs text-slate-500">
                      {i.resolvedBy} · {i.memo}
                    </span>
                  )}
                </td>
              </tr>
            ))}
          </Table>
        )}
      </Card>

      <ConfirmModal
        open={target !== null}
        title={`이슈 #${target?.id} 해결 처리`}
        body={
          <div className="space-y-3">
            <p>
              해결 처리는 <b>검토 완료 표시</b>일 뿐 잔액을 고치지 않습니다. 잔액 정정이
              필요하면 지급·회수 화면에서 사유와 함께 진행하세요.
            </p>
            <textarea
              className={`${inputClass} h-24 resize-none`}
              placeholder="처리 근거 (필수) — 예: 지갑 수동 조작 확인, ADMIN_REVOKE #123으로 정정"
              value={memo}
              onChange={(e) => setMemo(e.target.value)}
            />
          </div>
        }
        confirmLabel="해결 처리"
        onConfirm={() => void resolve()}
        onCancel={() => {
          setTarget(null)
          setMemo('')
        }}
      />
    </div>
  )
}
