import { useRef, useState } from 'react'
import Button from '../../components/Button'
import { ErrorState } from '../../components/Feedback'
import { useAuth } from './auth-context'

function AccountMenu({ disabled = false }: { disabled?: boolean }) {
  const { account, signOut } = useAuth()
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
    <div className="space-y-2">
      <div className="flex flex-wrap items-center justify-end gap-2 text-sm">
        <span>{account?.displayName}</span>
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
