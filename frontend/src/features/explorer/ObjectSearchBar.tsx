import { useState } from 'react'
import type { ObjectFilters } from './api'

export type ObjectTypeOption = {
  id: number
  name: string
}

type ObjectSearchBarProps = {
  filters: ObjectFilters
  objectTypes: ObjectTypeOption[]
  onSearch: (filters: ObjectFilters) => void
}

const labelClass = 'mb-1.5 block text-xs font-semibold text-slate-700'
const fieldClass =
  'w-full min-w-0 rounded-lg border border-slate-300 px-3 py-1.5 text-sm disabled:bg-slate-100 disabled:text-slate-400'

function ObjectSearchBar({
  filters,
  objectTypes,
  onSearch,
}: ObjectSearchBarProps) {
  const [typeId, setTypeId] = useState(
    filters.objectTypeId ? String(filters.objectTypeId) : '',
  )
  const [keyword, setKeyword] = useState(filters.q ?? '')

  function handleReset() {
    setTypeId('')
    setKeyword('')
    onSearch({})
  }

  return (
    <form
      className="grid gap-3 rounded-lg bg-white p-4 lg:grid-cols-[140px_minmax(190px,1.4fr)_150px_minmax(150px,1fr)_auto] lg:items-end"
      onSubmit={(event) => {
        event.preventDefault()
        onSearch({
          q: keyword.trim() || undefined,
          objectTypeId: typeId ? Number(typeId) : undefined,
        })
      }}
    >
      <div>
        <label htmlFor="object-type-filter" className={labelClass}>
          객체 유형
        </label>
        <select
          id="object-type-filter"
          value={typeId}
          onChange={(event) => setTypeId(event.target.value)}
          className={fieldClass}
        >
          <option value="">전체</option>
          {objectTypes.map((type) => (
            <option key={type.id} value={type.id}>
              {type.name}
            </option>
          ))}
        </select>
      </div>

      <div>
        <label htmlFor="object-search" className={labelClass}>
          ID · 이름 검색
        </label>
        <input
          id="object-search"
          type="search"
          value={keyword}
          onChange={(event) => setKeyword(event.target.value)}
          placeholder="예: PR-03, 프레스"
          className={fieldClass}
        />
      </div>

      {/* 076에 속성 조건 검색이 아직 없어서 Figma 자리만 두고 막아 둔다. */}
      <div>
        <label htmlFor="property-filter" className={labelClass}>
          속성 조건
        </label>
        <select id="property-filter" disabled className={fieldClass}>
          <option>전체 속성</option>
        </select>
      </div>

      <div>
        <label htmlFor="property-value" className={labelClass}>
          속성 값 · 포함 검색
        </label>
        <input
          id="property-value"
          disabled
          placeholder="백엔드 지원 후 사용"
          className={fieldClass}
        />
      </div>

      <div className="flex gap-2">
        <button
          type="submit"
          className="rounded-lg bg-slate-900 px-3 py-1.5 text-sm font-medium text-white"
        >
          검색
        </button>
        <button
          type="button"
          onClick={handleReset}
          className="rounded-lg border border-slate-300 px-3 py-1.5 text-sm text-slate-700"
        >
          초기화
        </button>
      </div>
    </form>
  )
}

export default ObjectSearchBar
