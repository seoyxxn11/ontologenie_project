import { useId, useState } from 'react'

export default function RuleSelect({
  label,
  value,
  options,
  onChange,
  disabled = false,
}: {
  label: string
  value: string
  options: { value: string; name: string; code: string }[]
  onChange: (value: string) => void
  disabled?: boolean
}) {
  const id = useId()
  const [search, setSearch] = useState('')
  const visible = options.filter(
    (option) =>
      option.value === value ||
      `${option.name} ${option.code}`
        .toLocaleLowerCase()
        .includes(search.toLocaleLowerCase()),
  )
  return (
    <div className="flex flex-col gap-2 text-sm">
      <label htmlFor={`${id}-search`}>{label} 검색</label>
      <input
        id={`${id}-search`}
        type="search"
        className="rounded-lg border border-slate-300 p-2"
        value={search}
        onChange={(event) => setSearch(event.target.value)}
        disabled={disabled}
        placeholder="이름 또는 코드"
      />
      <label htmlFor={id}>{label}</label>
      <select
        id={id}
        className="min-w-0 rounded-lg border border-slate-300 p-2"
        value={value}
        onChange={(event) => onChange(event.target.value)}
        disabled={disabled}
      >
        <option value="">선택하세요</option>
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
      {!disabled && !visible.length && (
        <p role="status">선택 가능한 검색 결과가 없습니다.</p>
      )}
    </div>
  )
}
