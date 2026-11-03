import type { ReactNode } from 'react'

const tones = {
  neutral: 'bg-slate-100 text-slate-700',
  info: 'bg-blue-50 text-blue-800',
  success: 'bg-emerald-50 text-emerald-800',
  warning: 'bg-amber-50 text-amber-800',
  danger: 'bg-red-50 text-red-700',
}

function StatusBadge({
  children,
  tone = 'neutral',
}: {
  children: ReactNode
  tone?: keyof typeof tones
}) {
  return (
    <span
      className={`inline-flex shrink-0 whitespace-nowrap rounded-full px-2 py-0.5 text-xs font-medium ${tones[tone]}`}
    >
      {children}
    </span>
  )
}

export default StatusBadge
