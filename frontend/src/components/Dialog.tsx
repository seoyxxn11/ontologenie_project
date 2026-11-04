import { useEffect, useId, useRef, type ReactNode } from 'react'
import Button from './Button'

type DialogProps = {
  title: string
  onClose: () => void
  children: ReactNode
  // wide: 표 두 개를 나란히 두는 선택 창처럼 넓은 내용
  size?: 'default' | 'wide'
}

// 화면 위에 뜨는 팝업 공통 틀. [닫기], Esc 키, 바깥 배경 클릭으로 닫힌다.
function Dialog({ title, onClose, children, size = 'default' }: DialogProps) {
  const titleId = useId()
  const panelRef = useRef<HTMLDivElement>(null)
  const closeRef = useRef(onClose)

  useEffect(() => {
    closeRef.current = onClose
  }, [onClose])

  useEffect(() => {
    const previousFocus = document.activeElement
    const panel = panelRef.current
    const previousOverflow = document.body.style.overflow
    document.body.style.overflow = 'hidden'
    panel?.focus()

    function focusableElements() {
      return Array.from(
        panel?.querySelectorAll<HTMLElement>(
          'button, a[href], input, select, textarea, summary, [tabindex]',
        ) ?? [],
      ).filter(
        (element) =>
          element.tabIndex >= 0 &&
          !element.matches(':disabled, [hidden], input[type="hidden"]') &&
          !element.closest('[hidden], [inert]'),
      )
    }
    function handleKeyDown(event: KeyboardEvent) {
      if (event.key === 'Escape') {
        event.preventDefault()
        event.stopPropagation()
        closeRef.current()
      }
      if (event.key === 'Tab') {
        const elements = focusableElements()
        const first = elements[0]
        const last = elements[elements.length - 1]
        const active = document.activeElement
        if (!first) {
          event.preventDefault()
          panel?.focus()
        } else if (event.shiftKey && (active === first || active === panel)) {
          event.preventDefault()
          last.focus()
        } else if (!event.shiftKey && (active === last || active === panel)) {
          event.preventDefault()
          first.focus()
        }
      }
    }
    function containFocus(event: FocusEvent) {
      if (event.target instanceof Node && !panel?.contains(event.target))
        panel?.focus()
    }
    document.addEventListener('keydown', handleKeyDown)
    document.addEventListener('focusin', containFocus)
    return () => {
      document.removeEventListener('keydown', handleKeyDown)
      document.removeEventListener('focusin', containFocus)
      document.body.style.overflow = previousOverflow
      if (previousFocus instanceof HTMLElement && previousFocus.isConnected)
        previousFocus.focus()
    }
  }, [])

  return (
    <div
      className="fixed inset-0 z-10 flex items-center justify-center bg-slate-900/40 p-4"
      onClick={(event) => {
        if (event.target === event.currentTarget) {
          onClose()
        }
      }}
    >
      <div
        ref={panelRef}
        tabIndex={-1}
        role="dialog"
        aria-modal="true"
        aria-labelledby={titleId}
        className={`max-h-[85vh] w-full ${size === 'wide' ? 'max-w-5xl' : 'max-w-2xl'} overflow-y-auto rounded-xl bg-white p-6`}
      >
        <div className="flex items-start justify-between gap-4">
          <h2 id={titleId} className="text-lg font-semibold text-slate-900">
            {title}
          </h2>
          <Button variant="secondary" onClick={onClose}>
            닫기
          </Button>
        </div>
        {children}
      </div>
    </div>
  )
}

export default Dialog
