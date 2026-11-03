import { useRef, useState, type FormEvent } from 'react'
import { Navigate } from 'react-router'
import Button from '../../components/Button'
import { ErrorState } from '../../components/Feedback'
import { changePassword } from './api'
import { ApiError } from '../../api/errors'
import AccountMenu from './AccountMenu'
import { useAuth } from './auth-context'

function PasswordChangePage() {
  const { account, refresh } = useAuth()
  const [current, setCurrent] = useState('')
  const [next, setNext] = useState('')
  const [confirmation, setConfirmation] = useState('')
  const [pending, setPending] = useState(false)
  const [error, setError] = useState<Error | null>(null)
  const submitting = useRef(false)
  async function submit(event: FormEvent) {
    event.preventDefault()
    if (submitting.current) return
    if (next !== confirmation) {
      setError(new Error('새 비밀번호 확인이 일치하지 않습니다.'))
      return
    }
    submitting.current = true
    setPending(true)
    setError(null)
    try {
      await changePassword(current, next)
      const result = await refresh()
      if (result.error) throw result.error
    } catch (cause) {
      if (cause instanceof ApiError && cause.status === 401) await refresh()
      setError(
        cause instanceof Error
          ? cause
          : new Error('비밀번호를 변경하지 못했습니다.'),
      )
    } finally {
      setCurrent('')
      setNext('')
      setConfirmation('')
      submitting.current = false
      setPending(false)
    }
  }
  if (!account?.mustChangePassword) return <Navigate to="/explorer" replace />
  return (
    <main className="min-h-screen bg-slate-100 p-6">
      <section className="mx-auto max-w-lg space-y-6 rounded-2xl bg-white p-8">
        <AccountMenu disabled={pending} />
        <h1 className="text-2xl font-semibold">비밀번호 변경</h1>
        <p className="text-sm text-slate-600">
          임시 비밀번호를 변경해야 업무 화면을 사용할 수 있습니다. 8자 이상,
          영문·숫자·특수문자를 포함하고 72바이트 이내로 입력해 주세요. 로그인 ID
          및 현재 비밀번호와 달라야 합니다.
        </p>
        <form
          onSubmit={(event) => {
            void submit(event)
          }}
          className="space-y-4"
        >
          {[
            {
              id: 'current-password',
              label: '현재 비밀번호',
              value: current,
              set: setCurrent,
              autoComplete: 'current-password',
            },
            {
              id: 'new-password',
              label: '새 비밀번호',
              value: next,
              set: setNext,
              autoComplete: 'new-password',
            },
            {
              id: 'confirm-password',
              label: '새 비밀번호 확인',
              value: confirmation,
              set: setConfirmation,
              autoComplete: 'new-password',
            },
          ].map((field) => (
            <div key={field.id}>
              <label htmlFor={field.id} className="mb-1 block text-sm">
                {field.label}
              </label>
              <input
                id={field.id}
                type="password"
                autoComplete={field.autoComplete}
                required
                value={field.value}
                onChange={(event) => field.set(event.target.value)}
                disabled={pending}
                className="w-full rounded-lg border border-slate-300 p-3"
              />
            </div>
          ))}
          {error && <ErrorState error={error} />}
          <Button type="submit" pending={pending}>
            {pending ? '변경 중…' : '비밀번호 변경'}
          </Button>
        </form>
      </section>
    </main>
  )
}

export default PasswordChangePage
