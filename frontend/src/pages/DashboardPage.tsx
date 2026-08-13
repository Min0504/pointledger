import { useEffect, useState } from 'react'
import { Link } from 'react-router-dom'
import { api } from '../api/client'
import type { Dashboard } from '../api/types'
import { Card, Empty, StatCard, Table } from '../components/ui'
import { formatPoints, typeLabel } from '../lib/format'

export default function DashboardPage() {
  const [data, setData] = useState<Dashboard | null>(null)
  const [error, setError] = useState<string | null>(null)

  useEffect(() => {
    api.dashboard().then(setData).catch((e: Error) => setError(e.message))
  }, [])

  if (error) return <Empty text={`불러오기 실패: ${error}`} />
  if (!data) return <Empty text="불러오는 중…" />

  return (
    <div className="space-y-6">
      <div className="grid grid-cols-2 gap-4 lg:grid-cols-4">
        <StatCard label="유통 잔액 (전체 지갑 합)" value={formatPoints(data.circulating)} sub={`지갑 ${data.wallets.toLocaleString()}개`} />
        <StatCard label="오늘 적립" value={formatPoints(sumOf(data, 'EARN', 'ADMIN_GRANT'))} />
        <StatCard label="오늘 사용" value={formatPoints(sumOf(data, 'REDEEM', 'ADMIN_REVOKE'))} />
        <StatCard
          label="미해결 대사 이슈"
          value={String(data.unresolvedIssues)}
          sub={data.draftSettlements > 0 ? `미확정 정산서 ${data.draftSettlements}건` : undefined}
          tone={data.unresolvedIssues > 0 ? 'warn' : 'default'}
        />
      </div>

      <Card title={`오늘의 원장 흐름 (${data.date})`}>
        {data.byType.length === 0 ? (
          <Empty text="오늘 기록된 원장이 아직 없습니다." />
        ) : (
          <Table head={['유형', '건수', '금액']}>
            {data.byType.map((s) => (
              <tr key={s.type}>
                <td className="px-3 py-2">{typeLabel(s.type)}</td>
                <td className="px-3 py-2 tabular-nums">{s.count.toLocaleString()}</td>
                <td className="px-3 py-2 tabular-nums">{formatPoints(s.amount)}</td>
              </tr>
            ))}
          </Table>
        )}
        {data.unresolvedIssues > 0 && (
          <p className="mt-4 text-sm">
            <Link to="/issues" className="font-medium text-amber-600 underline-offset-2 hover:underline">
              미해결 이슈 {data.unresolvedIssues}건을 확인하세요 →
            </Link>
          </p>
        )}
      </Card>
    </div>
  )
}

function sumOf(data: Dashboard, ...types: string[]): number {
  return data.byType.filter((s) => types.includes(s.type)).reduce((acc, s) => acc + s.amount, 0)
}
