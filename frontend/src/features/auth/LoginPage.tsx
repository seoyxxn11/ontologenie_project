import { useRef, useState, type FormEvent } from 'react'
import { Navigate, useLocation } from 'react-router'
import Button from '../../components/Button'
import { ErrorState, LoadingState } from '../../components/Feedback'
import { useAuth } from './auth-context'
import './login.css'

function LoginPage() {
  const {
    account,
    pending: restoring,
    error: restoreError,
    refresh,
    signIn,
  } = useAuth()
  const location = useLocation()
  const [loginId, setLoginId] = useState('')
  const [password, setPassword] = useState('')
  const [pending, setPending] = useState(false)
  const [error, setError] = useState<Error | null>(null)
  const submitting = useRef(false)
  const from = (location.state as { from?: unknown } | null)?.from
  const destination =
    typeof from === 'string' &&
    from.startsWith('/') &&
    !from.startsWith('//') &&
    !from.includes('\\') &&
    !/^\/(login|change-password)(\/|\?|$)/.test(from)
      ? from
      : '/explorer'

  async function submit(event: FormEvent) {
    event.preventDefault()
    if (submitting.current) return
    submitting.current = true
    setPending(true)
    setError(null)
    try {
      await signIn({ loginId: loginId.trim(), password })
    } catch (cause) {
      setError(
        cause instanceof Error ? cause : new Error('로그인하지 못했습니다.'),
      )
    } finally {
      setPassword('')
      submitting.current = false
      setPending(false)
    }
  }

  if (account && !restoreError)
    return (
      <Navigate
        to={account.mustChangePassword ? '/change-password' : destination}
        replace
      />
    )
  return (
    <main className="auth-page">
      <header className="auth-brand">
        Ontolo<span>Genie</span>
      </header>
      <div className="auth-columns">
        <section className="auth-introduction" aria-label="서비스 소개">
          <p className="auth-eyebrow">CONNECTED DATA. CLEARER DECISIONS.</p>
          <h1>
            데이터를 연결하고,
            <br />
            업무의 가능성을 넓히세요<span>.</span>
          </h1>
          <div className="auth-art" aria-hidden="true">
            <div className="auth-platform" />
            <div className="auth-pillar auth-pillar-one" />
            <div className="auth-pillar auth-pillar-two" />
            <div className="auth-pillar auth-pillar-three" />
            <div className="auth-orbit" />
          </div>
        </section>
        <section className="auth-card" aria-labelledby="login-heading">
          <p className="auth-eyebrow">WELCOME TO OntoloGenie</p>
          <h2 id="login-heading">워크스페이스에 로그인</h2>
          <p className="auth-description">
            연결된 데이터로 더 명확한 업무를 시작하세요.
          </p>
          {restoring ? (
            <LoadingState message="인증 상태를 확인하는 중…" />
          ) : restoreError ? (
            <ErrorState
              error={restoreError}
              onRetry={() => {
                void refresh()
              }}
            />
          ) : (
            <form
              onSubmit={(event) => {
                void submit(event)
              }}
              className="auth-form"
            >
              <label htmlFor="login-id">계정 ID</label>
              <input
                id="login-id"
                name="username"
                autoComplete="username"
                required
                maxLength={100}
                value={loginId}
                onChange={(event) => setLoginId(event.target.value)}
                disabled={pending}
              />
              <label htmlFor="login-password">비밀번호</label>
              <input
                id="login-password"
                name="password"
                type="password"
                autoComplete="current-password"
                required
                value={password}
                onChange={(event) => setPassword(event.target.value)}
                disabled={pending}
              />
              {error && (
                <ErrorState error={error} title="로그인하지 못했습니다." />
              )}
              <Button type="submit" pending={pending} className="auth-submit">
                {pending ? '로그인 중…' : '로그인'}
                <span aria-hidden="true">→</span>
              </Button>
            </form>
          )}
        </section>
      </div>
    </main>
  )
}

export default LoginPage
