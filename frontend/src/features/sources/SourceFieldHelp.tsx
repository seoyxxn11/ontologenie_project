import { useId } from 'react'

export default function SourceFieldHelp({
  label,
  children,
}: {
  label: string
  children: string
}) {
  const id = useId()
  const anchor = `--source-help-${id.replace(/[^a-zA-Z0-9_-]/g, '')}`
  return (
    <span className="source-field-help">
      <button
        type="button"
        className="source-field-help-trigger"
        aria-label={`${label} 도움말`}
        popoverTarget={id}
        style={{ anchorName: anchor }}
      >
        <svg
          width="16"
          height="16"
          viewBox="0 0 24 24"
          fill="none"
          stroke="currentColor"
          strokeWidth="1.5"
          aria-hidden="true"
        >
          <circle cx="12" cy="12" r="9" />
          <path d="M12 11v6M12 7v1" />
        </svg>
      </button>
      <span
        id={id}
        popover="auto"
        className="source-field-help-content"
        style={{ positionAnchor: anchor }}
      >
        {children}
      </span>
    </span>
  )
}
