import { useEffect, useRef, useState, type FormEvent } from 'react'
import { Navigate, useLocation } from 'react-router'
import Button from '../../components/Button'
import { ErrorState, LoadingState } from '../../components/Feedback'
import { VoicePoweredOrb } from '../../components/ui/voice-powered-orb'
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
  const [showEntrance, setShowEntrance] = useState(
    () => document.documentElement.dataset.uiInput !== 'keyboard',
  )
  const submitting = useRef(false)

  useEffect(() => {
    if (!showEntrance) return
    const finishEntrance = () => setShowEntrance(false)
    document.addEventListener('keydown', finishEntrance, true)
    return () => document.removeEventListener('keydown', finishEntrance, true)
  }, [showEntrance])
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
        <img
          src="/ontologenie-logo.svg"
          alt="OntoloGenie"
          width="250"
          height="32"
        />
      </header>
      <div className="auth-columns">
        <div className="auth-introduction" aria-hidden="true">
          <div className="auth-orb">
            <VoicePoweredOrb enableVoiceControl={false} />
          </div>
        </div>
        <section
          className={`auth-card${showEntrance && !restoring && !restoreError ? ' auth-card--ready' : ''}`}
          aria-labelledby="login-heading"
          onAnimationEnd={(event) => {
            if (event.target === event.currentTarget) setShowEntrance(false)
          }}
        >
          <h1 id="login-heading">워크스페이스에 로그인</h1>
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
              <div className="auth-field">
                <label htmlFor="login-id">계정 ID</label>
                <input
                  id="login-id"
                  name="username"
                  autoComplete="username"
                  placeholder="계정 ID를 입력하세요"
                  required
                  maxLength={100}
                  value={loginId}
                  onChange={(event) => setLoginId(event.target.value)}
                  disabled={pending}
                />
              </div>
              <div className="auth-field">
                <label htmlFor="login-password">비밀번호</label>
                <input
                  id="login-password"
                  name="password"
                  type="password"
                  autoComplete="current-password"
                  placeholder="비밀번호를 입력하세요"
                  required
                  value={password}
                  onChange={(event) => setPassword(event.target.value)}
                  disabled={pending}
                />
              </div>
              {error && (
                <ErrorState error={error} title="로그인하지 못했습니다." />
              )}
              <Button type="submit" pending={pending} className="auth-submit">
                {pending ? '로그인 중…' : '로그인'}
              </Button>
            </form>
          )}
        </section>
      </div>
    </main>
  )
}

export default LoginPage
