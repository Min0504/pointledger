import { useCallback, useEffect, useState } from 'react'
import { ApiError, api } from '../api/client'
import type { Settlement } from '../api/types'
import { Badge, Button, Card, ConfirmModal, Empty, ErrorNote, Table, inputClass } from '../components/ui'
import { formatDateTime, formatPoints } from '../lib/format'

/** 정산서 조회·확정 — 확정하면 동결되고, 이후 어긋남은 대사가 발견한다 */
export default function SettlementsPage() {
  const [date, setDate] = useState('')
  const [items, setItems] = useState<Settlement[]>([])
  const [error, setError] = useState<string | null>(null)
  const [target, setTarget] = useState<Settlement | null>(null)

  const load = useCallback(() => {
    api.settlements(date || null, null)
      .then((res) => setItems(res.items))
      .catch((e: Error) => setError(e.message))
  }, [date])

  useEffect(load, [load])

  async function confirm() {
    if (!target) return
    try {
      await api.confirmSettlement(target.id)
      setTarget(null)
      load()
    } catch (err) {
      setTarget(null)
      setError(err instanceof ApiError ? `${err.code}: ${err.message}` : (err as Error).message)
    }
  }

  return (
    <div className="space-y-4">
      <Card title="정산서">
        <div className="mb-4 flex items-end gap-2">
          <label className="block">
            <span className="mb-1 block text-xs font-medium text-slate-500">정산일 필터</span>
            <input type="date" className={inputClass} value={date} onChange={(e) => setDate(e.target.value)} />
          </label>
          {date && <Button variant="ghost" onClick={() => setDate('')}>전체 보기</Button>}
        </div>
        <ErrorNote message={error} />
        {items.length === 0 ? (
          <Empty text="정산서가 없습니다. 배치 화면에서 정산을 실행할 수 있습니다." />
        ) : (
          <Table head={['#', '정산일', '가맹점', '총액', '상태', '확정', '']}>
            {items.map((s) => (
              <tr key={s.id}>
                <td className="px-3 py-2 tabular-nums text-slate-400">{s.id}</td>
                <td className="px-3 py-2 tabular-nums">{s.settleDate}</td>
                <td className="px-3 py-2">{s.merchantName ?? `가맹점 ${s.merchantId}`}</td>
                <td className={`px-3 py-2 tabular-nums font-medium ${s.totalAmount < 0 ? 'text-rose-600' : ''}`}>
                  {formatPoints(s.totalAmount)}
                  {s.totalAmount < 0 && <span className="ml-1 text-xs font-normal text-slate-400">(차감 정산)</span>}
                </td>
                <td className="px-3 py-2">
                  <Badge tone={s.status === 'CONFIRMED' ? 'green' : 'blue'}>
                    {s.status === 'CONFIRMED' ? '확정' : '작성 중'}
                  </Badge>
                </td>
                <td className="px-3 py-2 text-xs text-slate-500">
                  {s.confirmedBy ? `${s.confirmedBy} · ${formatDateTime(s.confirmedAt)}` : '—'}
                </td>
                <td className="px-3 py-2">
                  {s.status === 'DRAFT' && (
                    <Button variant="ghost" onClick={() => setTarget(s)}>확정</Button>
                  )}
                </td>
              </tr>
            ))}
          </Table>
        )}
      </Card>

      <ConfirmModal
        open={target !== null}
        title="정산 확정"
        body={
          <p>
            <b>{target?.settleDate}</b> · <b>{target?.merchantName ?? `가맹점 ${target?.merchantId}`}</b>{' '}
            정산서(<b>{formatPoints(target?.totalAmount ?? 0)}</b>)를 확정합니다. 확정 후에는
            재집계되지 않으며, 늦게 도착하는 취소는 다음 날 차감 정산으로 이월됩니다.
          </p>
        }
        confirmLabel="확정"
        onConfirm={() => void confirm()}
        onCancel={() => setTarget(null)}
      />
    </div>
  )
}
