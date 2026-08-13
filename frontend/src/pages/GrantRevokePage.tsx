import { useMemo, useState, type FormEvent } from 'react'
import { ApiError, api } from '../api/client'
import { Button, Card, ConfirmModal, ErrorNote, Field, inputClass } from '../components/ui'
import { formatPoints } from '../lib/format'

type Mode = 'grant' | 'revoke'

/** 수동 지급/회수 — 사유 필수 + 확인 모달. 제출은 멱등키를 실어 보낸다 */
export default function GrantRevokePage() {
  const [mode, setMode] = useState<Mode>('grant')
  const [userId, setUserId] = useState('')
  const [amount, setAmount] = useState('')
  const [reason, setReason] = useState('')
  const [expireDays, setExpireDays] = useState('365')
  const [confirming, setConfirming] = useState(false)
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [done, setDone] = useState<string | null>(null)
  // 폼 한 번의 제출 시도들이 같은 키를 공유한다 — 더블클릭·네트워크 재시도가
  // 두 번 기장되지 않는 근거. 입력이 바뀌면 새 논리적 요청이므로 키도 새로
  const idemKey = useMemo(() => crypto.randomUUID(), [mode, userId, amount, reason, expireDays]) // eslint-disable-line react-hooks/exhaustive-deps

  const valid = Number(userId) > 0 && Number(amount) > 0 && reason.trim().length > 0

  function onSubmit(e: FormEvent) {
    e.preventDefault()
    if (valid) setConfirming(true)
  }

  async function execute() {
    setConfirming(false)
    setBusy(true)
    setError(null)
    setDone(null)
    try {
      if (mode === 'grant') {
        const res = await api.grant(
          { userId: Number(userId), amount: Number(amount), reason: reason.trim(), expireDays: Number(expireDays) },
          idemKey,
        )
        setDone(`지급 완료 — 원장 #${res.entryId}, 잔액 ${formatPoints(res.balanceAfter)}`)
      } else {
        const res = await api.revoke(
          { userId: Number(userId), amount: Number(amount), reason: reason.trim() },
          idemKey,
        )
        setDone(`회수 완료 — 원장 #${res.entryId}, 잔액 ${formatPoints(res.balanceAfter)}`)
      }
      setAmount('')
      setReason('')
    } catch (err) {
      setError(err instanceof ApiError ? `${err.code}: ${err.message}` : (err as Error).message)
    } finally {
      setBusy(false)
    }
  }

  return (
    <div className="max-w-xl space-y-6">
      <Card title="수동 지급 · 회수">
        <div className="mb-4 flex gap-1 rounded-lg bg-slate-100 p-1">
          {(['grant', 'revoke'] as const).map((m) => (
            <button
              key={m}
              onClick={() => setMode(m)}
              className={`flex-1 rounded-md py-1.5 text-sm font-medium transition ${
                mode === m ? 'bg-white shadow-sm' : 'text-slate-500'
              }`}
            >
              {m === 'grant' ? '지급 (ADMIN_GRANT)' : '회수 (ADMIN_REVOKE)'}
            </button>
          ))}
        </div>

        <form onSubmit={onSubmit} className="space-y-4">
          <div className="grid grid-cols-2 gap-4">
            <Field label="사용자 ID">
              <input className={inputClass} value={userId} onChange={(e) => setUserId(e.target.value)} inputMode="numeric" required />
            </Field>
            <Field label="금액 (P)">
              <input className={inputClass} value={amount} onChange={(e) => setAmount(e.target.value)} inputMode="numeric" required />
            </Field>
          </div>
          {mode === 'grant' && (
            <Field label="만료일 (일)">
              <input className={inputClass} value={expireDays} onChange={(e) => setExpireDays(e.target.value)} inputMode="numeric" required />
            </Field>
          )}
          <Field label="사유 (필수 — 원장에 그대로 남습니다)">
            <textarea
              className={`${inputClass} h-20 resize-none`}
              value={reason}
              onChange={(e) => setReason(e.target.value)}
              placeholder="예: CS-1234 보상 지급"
              required
            />
          </Field>
          <ErrorNote message={error} />
          {done && <p className="rounded-lg bg-emerald-50 px-3 py-2 text-sm text-emerald-700">{done}</p>}
          <Button type="submit" variant={mode === 'revoke' ? 'danger' : 'primary'} disabled={!valid || busy}>
            {busy ? '처리 중…' : mode === 'grant' ? '지급하기' : '회수하기'}
          </Button>
        </form>
      </Card>

      <ConfirmModal
        open={confirming}
        title={mode === 'grant' ? '수동 지급 확인' : '수동 회수 확인'}
        danger={mode === 'revoke'}
        body={
          <p>
            사용자 <b>{userId}</b>에게서 <b>{formatPoints(Number(amount) || 0)}</b>를{' '}
            {mode === 'grant' ? '지급' : '회수'}합니다. 이 조작은 운영자 이메일·사유와 함께
            원장에 영구히 기록됩니다.
          </p>
        }
        confirmLabel={mode === 'grant' ? '지급' : '회수'}
        onConfirm={() => void execute()}
        onCancel={() => setConfirming(false)}
      />
    </div>
  )
}
