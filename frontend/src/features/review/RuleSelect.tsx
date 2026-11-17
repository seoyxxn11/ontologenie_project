import { useEffect, useId, useRef, useState } from 'react'

export default function RuleSelect({
  label,
  value,
  options,
  onChange,
  disabled = false,
  allowEmpty = true,
}: {
  label: string
  value: string
  options: { value: string; name: string; code: string }[]
  onChange: (value: string) => void
  disabled?: boolean
  allowEmpty?: boolean
}) {
  const id = useId()
  const [search, setSearch] = useState('')
  const [open, setOpen] = useState(false)
  const [active, setActive] = useState(-1)
  const listRef = useRef<HTMLDivElement>(null)
  const matches = options.filter((option) =>
    `${option.name} ${option.code}`
      .toLocaleLowerCase()
      .includes(search.toLocaleLowerCase()),
  )
  const visible = options.filter(
    (option) => option.value === value || matches.includes(option),
  )
  const expanded = open && !disabled
  useEffect(() => {
    if (expanded && active >= 0) {
      listRef.current?.children[active]?.scrollIntoView?.({ block: 'nearest' })
    }
  }, [expanded, active])
  function choose(next: string) {
    if (next !== value) onChange(next)
    setSearch('')
    setOpen(false)
    setActive(-1)
  }
  return (
    <div className="flex flex-col gap-2 text-sm">
      <label htmlFor={`${id}-search`}>{label} 검색</label>
      <div className="rule-select-search">
        <input
          id={`${id}-search`}
          type="search"
          role="combobox"
          aria-autocomplete="list"
          aria-expanded={expanded}
          aria-controls={`${id}-suggestions`}
          aria-activedescendant={
            expanded && active >= 0 && matches[active]
              ? `${id}-option-${active}`
              : undefined
          }
          className="rounded-lg border border-slate-300 p-2"
          value={search}
          onClick={() => setOpen(true)}
          onFocus={() => {
            setOpen(true)
            setActive(-1)
          }}
          onBlur={() => setOpen(false)}
          onChange={(event) => {
            setSearch(event.target.value)
            setOpen(true)
            setActive(-1)
          }}
          onKeyDown={(event) => {
            if (event.nativeEvent.isComposing) return
            if (event.key === 'Escape') {
              setOpen(false)
              setActive(-1)
            }
            if (event.key === 'ArrowDown' || event.key === 'ArrowUp') {
              event.preventDefault()
              setOpen(true)
              setActive((current) =>
                matches.length
                  ? event.key === 'ArrowDown'
                    ? Math.min(current + 1, matches.length - 1)
                    : Math.max(current - 1, 0)
                  : -1,
              )
            }
            if (event.key === 'Enter' && expanded) {
              event.preventDefault()
              if (active >= 0 && matches[active]) choose(matches[active].value)
            }
          }}
          disabled={disabled}
          placeholder="이름 또는 코드"
        />
        {expanded && (
          <div
            id={`${id}-suggestions`}
            ref={listRef}
            role="listbox"
            aria-label={`${label} 후보`}
            className="rule-select-suggestions"
          >
            {matches.map((option, index) => (
              <div
                key={option.value}
                id={`${id}-option-${index}`}
                role="option"
                tabIndex={-1}
                aria-selected={option.value === value}
                className={active === index ? 'is-active' : undefined}
                onMouseDown={(event) => event.preventDefault()}
                onClick={() => choose(option.value)}
              >
                <span>{option.name}</span>
                <small>{option.code}</small>
              </div>
            ))}
            {!matches.length && (
              <p role="status">
                검색 결과가 없습니다. 검색어를 지우고 다시 선택하세요.
              </p>
            )}
          </div>
        )}
      </div>
      <label htmlFor={id}>{label}</label>
      <select
        id={id}
        className="min-w-0 rounded-lg border border-slate-300 p-2"
        value={value}
        onChange={(event) => choose(event.target.value)}
        disabled={disabled}
      >
        {allowEmpty && <option value="">선택하세요</option>}
        {value && !options.some((option) => option.value === value) && (
          <option value={value}>
            기존 값: {value} · 목록에서 확인되지 않음
          </option>
        )}
        {visible.map((option) => (
          <option key={option.value} value={option.value}>
            {option.name} · 코드: {option.code}
          </option>
        ))}
      </select>
      {!disabled && !matches.length && !expanded && (
        <p role="status">선택 가능한 검색 결과가 없습니다.</p>
      )}
    </div>
  )
}
