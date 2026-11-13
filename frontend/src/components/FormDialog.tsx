import { useCallback, useEffect, useRef, useState, type ReactNode } from 'react'
import { useBeforeUnload, useBlocker } from 'react-router'
import Button from './Button'
import Dialog from './Dialog'

// 요청을 취소하지 않고 창만 접어, 응답 실패 시에도 같은 입력으로 돌아간다.
export default function FormDialog({
  title,
  size,
  dirty,
  pending,
  error,
  onClose,
  children,
}: {
  title: string
  size?: 'default' | 'wide'
  dirty: boolean
  pending: boolean
  error?: Error | null
  onClose: (navigating?: boolean) => void
  children: ReactNode | ((requestClose: () => void) => ReactNode)
}) {
  const [confirmClose, setConfirmClose] = useState(false)
  const [minimized, setMinimized] = useState(false)
  const content = useRef<HTMLDivElement>(null)
  const confirmation = useRef<HTMLDivElement>(null)
  const [previousFocus, setPreviousFocus] = useState<HTMLElement | null>(null)
  const wasConfirming = useRef(false)
  const protect = dirty && !pending
  const blocker = useBlocker(
    ({ currentLocation, nextLocation }) =>
      protect &&
      (currentLocation.pathname !== nextLocation.pathname ||
        currentLocation.search !== nextLocation.search),
  )
  const confirming = confirmClose || blocker.state === 'blocked'
  useBeforeUnload(
    useCallback(
      (event) => {
        if (protect || pending) {
          event.preventDefault()
          event.returnValue = ''
        }
      },
      [protect, pending],
    ),
  )
  useEffect(() => {
    if (confirming) confirmation.current?.querySelector('button')?.focus()
    else if (wasConfirming.current) {
      if (previousFocus?.isConnected) previousFocus.focus()
      else
        content.current
          ?.querySelector<HTMLElement>('input, select, textarea')
          ?.focus()
    }
    wasConfirming.current = confirming
  }, [confirming, previousFocus])

  function requestClose() {
    if (pending) {
      setMinimized(true)
      return
    }
    if (confirming) {
      setConfirmClose(false)
      if (blocker.state === 'blocked') blocker.reset()
    } else if (protect) {
      setPreviousFocus(
        document.activeElement instanceof HTMLElement
          ? document.activeElement
          : null,
      )
      setConfirmClose(true)
    } else onClose()
  }

  if (minimized && !confirming)
    return (
      <section
        aria-label={`${title} 저장 상태`}
        className="ui-feedback space-y-3"
      >
        <p role={error ? 'alert' : 'status'}>
          {pending
            ? `${title} 요청을 처리 중입니다. 창을 닫아도 요청은 취소되지 않습니다.`
            : error
              ? '저장 응답을 확인해 주세요. 입력과 오류를 다시 열어 볼 수 있습니다.'
              : '저장 결과를 확인해 주세요.'}
        </p>
        <p>
          다른 화면으로 이동한 뒤에는 데이터 연결 화면의 저장 요청 상태에서
          결과와 대상을 확인하세요.
        </p>
        <Button variant="secondary" onClick={() => setMinimized(false)}>
          {error ? '입력·오류 확인' : '저장 상태 보기'}
        </Button>
      </section>
    )

  return (
    <Dialog
      title={title}
      size={size}
      onClose={requestClose}
      closeOnBackdrop={!dirty && !pending && !confirming}
    >
      {confirming ? (
        <div ref={confirmation}>
          <p role="alert" className="font-semibold">
            변경사항을 버릴까요?
          </p>
          <p className="mt-2">저장하지 않은 입력은 사라집니다.</p>
          <div className="mt-4 flex justify-end gap-2">
            <Button variant="secondary" onClick={requestClose}>
              계속 편집
            </Button>
            <Button
              variant="danger"
              onClick={() => {
                if (blocker.state === 'blocked') {
                  onClose(true)
                  blocker.proceed()
                } else onClose()
              }}
            >
              변경사항 버리기
            </Button>
          </div>
        </div>
      ) : (
        <div ref={content}>
          {pending && (
            <p role="status" className="mb-4">
              저장 요청을 처리 중입니다. 닫아도 요청은 취소되지 않으며, 저장
              상태 보기로 결과를 확인할 수 있습니다.
            </p>
          )}
          {typeof children === 'function' ? children(requestClose) : children}
        </div>
      )}
    </Dialog>
  )
}
