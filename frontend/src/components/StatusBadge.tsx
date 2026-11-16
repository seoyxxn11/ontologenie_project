import type { ReactNode } from 'react'
import StatusDot from './ui/status-dot'

const tones = {
  neutral: 'neutral',
  info: 'active',
  success: 'success',
  warning: 'warning',
  danger: 'error',
} as const

function StatusBadge({
  children,
  tone = 'neutral',
  animate = false,
}: {
  children: ReactNode
  tone?: keyof typeof tones
  animate?: boolean
}) {
  return (
    <StatusDot
      tone={tones[tone]}
      label={children}
      animate={animate}
      showLabel
      className="ui-status-badge"
    />
  )
}

export default StatusBadge
