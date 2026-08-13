import { useCallback, useEffect, useState } from 'react'
import { ApiError, api } from '../api/client'
import type { Execution } from '../api/types'
import { Badge, Button, Card, Empty, ErrorNote, Table, inputClass } from '../components/ui'
import { formatDateTime } from '../lib/format'

const JOB_LABELS: Record<string, string> = {
  expirePointsJob: '만료',
  settleDailyJob: '정산',
  reconcileDailyJob: '대사',
}

function statusTone(status: string): 'green' | 'red' | 'amber' | 'slate' {
  if (status === 'COMPLETED') return 'green'
  if (status === 'FAILED') return 'red'
  if (status === 'STARTED' || status === 'STARTING') return 'amber'
  return 'slate'
}

/** 배치 실행 이력 + 수동 기동 — 놓친 날짜의 소급 실행이 용도다 */
export default function BatchPage() {
  const [executions, setExecutions] = useState<Execution[]>([])
  const [error, setError] = useState<string | null>(null)
  const [notice, setNotice] = useState<string | null>(null)
  const [date, setDate] = useState(() => new Date().toISOString().slice(0, 10))
  const [busy, setBusy] = useState(false)

  const load = useCallback(() => {
    api.executions().then(setExecutions).catch((e: Error) => setError(e.message))
  }, [])

  useEffect(load, [load])

  async function run(job: 'expire' | 'settle' | 'reconcile') {
    setBusy(true)
    setError(null)
    setNotice(null)
    try {
      const body =
        job === 'expire' ? { asOf: date } : job === 'settle' ? { settleDate: date } : { reconcileDate: date }
      const res = await api.runBatch(job, body)
      setNotice(
        res.status === 'SKIPPED'
          ? `${JOB_LABELS[res.jobName] ?? res.jobName} Job은 해당 날짜에 이미 완료되어 건너뜁니다.`
          : `${JOB_LABELS[res.jobName] ?? res.jobName} Job ${res.status} (실행 #${res.executionId ?? '—'})`,
      )
      load()
    } catch (err) {
      setError(err instanceof ApiError ? `${err.code}: ${err.message}` : (err as Error).message)
    } finally {
      setBusy(false)
    }
  }

  return (
    <div className="space-y-4">
      <Card title="수동 기동 — 스케줄(만료 04시 · 정산 05시 · 대사 06시 KST)을 놓친 날짜의 소급 실행">
        <div className="flex flex-wrap items-end gap-2">
          <label className="block">
            <span className="mb-1 block text-xs font-medium text-slate-500">대상 날짜</span>
            <input type="date" className={inputClass} value={date} onChange={(e) => setDate(e.target.value)} />
          </label>
          <Button variant="ghost" disabled={busy} onClick={() => void run('expire')}>만료 실행</Button>
          <Button variant="ghost" disabled={busy} onClick={() => void run('settle')}>정산 실행</Button>
          <Button variant="ghost" disabled={busy} onClick={() => void run('reconcile')}>대사 실행</Button>
        </div>
        <div className="mt-3 space-y-2">
          <ErrorNote message={error} />
          {notice && <p className="rounded-lg bg-sky-50 px-3 py-2 text-sm text-sky-700">{notice}</p>}
        </div>
      </Card>

      <Card
        title="실행 이력 — JobRepository 메타데이터"
        actions={<Button variant="ghost" onClick={load}>새로고침</Button>}
      >
        {executions.length === 0 ? (
          <Empty text="실행 이력이 없습니다." />
        ) : (
          <Table head={['#', 'Job', '상태', '종료 코드', '파라미터', '시작', '종료']}>
            {executions.map((e) => (
              <tr key={e.executionId}>
                <td className="px-3 py-2 tabular-nums text-slate-400">{e.executionId}</td>
                <td className="px-3 py-2">{JOB_LABELS[e.jobName] ?? e.jobName}</td>
                <td className="px-3 py-2"><Badge tone={statusTone(e.status)}>{e.status}</Badge></td>
                <td className="px-3 py-2 text-xs text-slate-500">{e.exitCode}</td>
                <td className="px-3 py-2 text-xs text-slate-500">
                  {Object.entries(e.parameters)
                    .filter(([k]) => k !== 'triggeredAt')
                    .map(([k, v]) => `${k}=${v}`)
                    .join(' ') || '—'}
                </td>
                <td className="px-3 py-2 text-xs tabular-nums text-slate-500">{formatDateTime(e.startTime)}</td>
                <td className="px-3 py-2 text-xs tabular-nums text-slate-500">{formatDateTime(e.endTime)}</td>
              </tr>
            ))}
          </Table>
        )}
      </Card>
    </div>
  )
}
