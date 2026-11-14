import { useId, type ButtonHTMLAttributes } from 'react'
import Button from '../../components/Button'

type AgentIcon = 'plus' | 'star' | 'send' | 'collapse' | 'expand'

type Props = ButtonHTMLAttributes<HTMLButtonElement> & {
  label: string
  icon: AgentIcon
  pending?: boolean
}

function AgentIconButton({
  label,
  icon,
  pending,
  className = '',
  ...props
}: Props) {
  const tooltipId = useId()
  return (
    <span className="agent-icon-control">
      <Button
        {...props}
        variant="text"
        pending={pending}
        aria-label={label}
        aria-describedby={tooltipId}
        className={`agent-icon-button ${className}`}
      >
        {!pending && (
          <svg
            aria-hidden="true"
            width="20"
            height="20"
            viewBox="0 0 24 24"
            fill="none"
            stroke="currentColor"
            strokeWidth="1.8"
            strokeLinecap="round"
            strokeLinejoin="round"
          >
            {icon === 'plus' && <path d="M12 5v14M5 12h14" />}
            {icon === 'star' && (
              <path d="m12 3 2.78 5.63L21 9.54l-4.5 4.39 1.06 6.2L12 17.2l-5.56 2.93 1.06-6.2L3 9.54l6.22-.91L12 3Z" />
            )}
            {icon === 'send' && <path d="M12 19V5m-7 7 7-7 7 7" />}
            {(icon === 'collapse' || icon === 'expand') && (
              <>
                <rect x="3" y="3" width="18" height="18" rx="2" />
                <path d="M9 3v18" />
                <path
                  d={icon === 'collapse' ? 'm16 9-3 3 3 3' : 'm14 9 3 3-3 3'}
                />
              </>
            )}
          </svg>
        )}
      </Button>
      <span id={tooltipId} role="tooltip" className="agent-control-tooltip">
        {label}
      </span>
    </span>
  )
}

export default AgentIconButton
