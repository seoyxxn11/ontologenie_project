import type { CSSProperties, HTMLAttributes, ReactNode } from 'react'
import './status-dot.css'

type StatusDotTone = 'neutral' | 'active' | 'success' | 'warning' | 'error'

type StatusDotProps = Omit<HTMLAttributes<HTMLSpanElement>, 'children'> & {
  tone: StatusDotTone
  label: ReactNode
  showLabel?: boolean
  animate?: boolean
  size?: 'sm' | 'md' | 'lg'
}

const sizes = {
  sm: { dot: 6, container: 14 },
  md: { dot: 8, container: 20 },
  lg: { dot: 10, container: 26 },
}

export default function StatusDot({
  tone,
  label,
  showLabel = false,
  animate = tone === 'active',
  size = 'md',
  className = '',
  style,
  ...props
}: StatusDotProps) {
  const dimensions = sizes[size]
  const dotStyle = {
    '--status-dot-size': `${dimensions.dot}px`,
    '--status-dot-container': `${dimensions.container}px`,
    ...style,
  } as CSSProperties

  return (
    <span
      {...props}
      className={`ui-status-dot ${className}`}
      data-tone={tone}
      style={dotStyle}
    >
      <span className="ui-status-dot-indicator" aria-hidden="true">
        {animate && (
          <>
            <span className="ui-status-dot-ripple" />
            <span className="ui-status-dot-ripple ui-status-dot-ripple--echo" />
          </>
        )}
        <span className="ui-status-dot-core" />
      </span>
      <span className={showLabel ? 'ui-status-dot-label' : 'sr-only'}>
        {label}
      </span>
    </span>
  )
}
