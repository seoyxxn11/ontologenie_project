import type { ButtonHTMLAttributes } from 'react'

type ButtonProps = ButtonHTMLAttributes<HTMLButtonElement> & {
  variant?: 'primary' | 'secondary'
  pending?: boolean
}

function Button({
  variant = 'primary',
  pending = false,
  disabled,
  children,
  className = '',
  type = 'button',
  ...props
}: ButtonProps) {
  return (
    <button
      {...props}
      type={type}
      disabled={disabled || pending}
      aria-busy={pending || undefined}
      className={`rounded-lg border px-3 py-1.5 text-sm font-medium focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-slate-700 disabled:cursor-not-allowed disabled:opacity-50 ${variant === 'primary' ? 'border-slate-900 bg-slate-900 text-white hover:bg-slate-700' : 'border-slate-300 bg-white text-slate-700 hover:bg-slate-50'} ${className}`}
    >
      {children}
    </button>
  )
}

export default Button
