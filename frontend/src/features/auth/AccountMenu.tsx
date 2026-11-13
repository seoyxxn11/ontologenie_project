import { useRef, useState } from 'react'
import Button from '../../components/Button'
import { ErrorState } from '../../components/Feedback'
import { useAuth } from './auth-context'
import { roleLabels } from './access'

function AccountMenu({
  disabled = false,
  compact = false,
}: {
  disabled?: boolean
  compact?: boolean
}) {
  const { account, signOut } = useAuth()
  const roleName =
    account && (roleLabels[account.roleCode] ?? '알 수 없는 역할')
  const [pending, setPending] = useState(false)
  const [error, setError] = useState<Error | null>(null)
  const submitting = useRef(false)
  async function submit() {
    if (submitting.current) return
    submitting.current = true
    setPending(true)
    setError(null)
    try {
      await signOut()
    } catch (cause) {
      setError(
        cause instanceof Error ? cause : new Error('로그아웃하지 못했습니다.'),
      )
    } finally {
      submitting.current = false
      setPending(false)
    }
  }
  return (
    <div className="account-menu space-y-2">
      <div className="flex flex-wrap items-center justify-end gap-2 text-sm">
        <span>
          {compact && account
            ? `${account.loginId} · ${roleName}`
            : account?.displayName}
        </span>
        {!compact && account && account.displayName.trim() !== roleName && (
          <span className="text-xs text-slate-500">{roleName}</span>
        )}
        <Button
          variant="secondary"
          disabled={disabled}
          pending={pending}
          onClick={() => {
            void submit()
          }}
        >
          {pending ? '로그아웃 중…' : '로그아웃'}
        </Button>
      </div>
      {error && <ErrorState error={error} />}
    </div>
  )
}

export default AccountMenu
