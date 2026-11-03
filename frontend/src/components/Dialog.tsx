import { useEffect, useId, type ReactNode } from 'react'

type DialogProps = {
  title: string
  onClose: () => void
  children: ReactNode
}

// 화면 위에 뜨는 팝업 공통 틀. [닫기], Esc 키, 바깥 배경 클릭으로 닫힌다.
function Dialog({ title, onClose, children }: DialogProps) {
  const titleId = useId()

  useEffect(() => {
    function handleKeyDown(event: KeyboardEvent) {
      if (event.key === 'Escape') {
        onClose()
      }
    }
    document.addEventListener('keydown', handleKeyDown)
    return () => document.removeEventListener('keydown', handleKeyDown)
  }, [onClose])

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
        role="dialog"
        aria-modal="true"
        aria-labelledby={titleId}
        className="max-h-[85vh] w-full max-w-2xl overflow-y-auto rounded-xl bg-white p-6"
      >
        <div className="flex items-start justify-between gap-4">
          <h2 id={titleId} className="text-lg font-semibold text-slate-900">
            {title}
          </h2>
          <button
            type="button"
            onClick={onClose}
            className="rounded-lg border border-slate-300 px-3 py-1 text-sm text-slate-700"
          >
            닫기
          </button>
        </div>
        {children}
      </div>
    </div>
  )
}

export default Dialog
