import { Link, useLocation } from 'react-router'

export default function HistoryBack({
  className,
  dividerClassName,
  label = '이전 화면으로',
}: {
  className: string
  dividerClassName: string
  label?: string
}) {
  const location = useLocation()
  const value: unknown = location.state?.historyReturnTo
  const returnTo =
    typeof value === 'string' &&
    /^\/(explorer|agent|issues|admin\/rules)([/?#]|$)/.test(value) &&
    !value.includes('\\') &&
    value.split(/[?#]/)[0] !== location.pathname
      ? value
      : null

  if (!returnTo) return null
  return (
    <>
      <Link
        className={className}
        to={returnTo}
        aria-label={label}
        data-tooltip={label}
      >
        <svg
          width="20"
          height="20"
          viewBox="0 0 24 24"
          fill="none"
          stroke="currentColor"
          strokeWidth="1.8"
          aria-hidden="true"
        >
          <path d="m12 5-7 7 7 7M5 12h14" />
        </svg>
      </Link>
      <span className={dividerClassName} aria-hidden="true" />
    </>
  )
}
