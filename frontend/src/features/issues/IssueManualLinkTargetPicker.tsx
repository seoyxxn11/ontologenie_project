import { useQuery } from '@tanstack/react-query'
import { useState, type FormEvent } from 'react'
import Button from '../../components/Button'
import { EmptyState, ErrorState, LoadingState } from '../../components/Feedback'
import Pagination from '../../components/Pagination'
import { fetchObjects, fetchObjectTypes } from '../explorer/api'
import type { ObjectInstance } from '../explorer/types'

export default function IssueManualLinkTargetPicker({
  sourceObjectId,
  value,
  onChange,
}: {
  sourceObjectId: number
  value: ObjectInstance | null
  onChange: (object: ObjectInstance | null) => void
}) {
  const [typeId, setTypeId] = useState('')
  const [text, setText] = useState('')
  const [filters, setFilters] = useState<{
    objectTypeId: number
    q: string
  } | null>(null)
  const [page, setPage] = useState(0)
  const types = useQuery({
    queryKey: ['objects', 'types'],
    queryFn: fetchObjectTypes,
    retry: false,
  })
  const current =
    filters !== null &&
    filters.objectTypeId === Number(typeId) &&
    filters.q === text.trim() &&
    types.isSuccess &&
    types.data.some((type) => type.id === filters.objectTypeId)
  const results = useQuery({
    queryKey: ['issues', 'manual-link-targets', sourceObjectId, filters, page],
    queryFn: () => fetchObjects(filters!, page),
    enabled: current,
    retry: false,
    refetchOnWindowFocus: false,
    refetchOnReconnect: false,
  })

  function search(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    const objectTypeId = Number(typeId)
    if (!types.data?.some((type) => type.id === objectTypeId)) return
    onChange(null)
    setPage(0)
    setFilters({ objectTypeId, q: text.trim() })
    if (current && page === 0) void results.refetch()
  }

  return (
    <div className="space-y-3">
      {types.isPending && <LoadingState message="객체 유형을 불러오는 중…" />}
      {types.isError && (
        <ErrorState error={types.error} onRetry={() => void types.refetch()} />
      )}
      {types.isSuccess && types.data.length === 0 && (
        <EmptyState message="조회할 수 있는 객체 유형이 없습니다." />
      )}
      <form onSubmit={search} className="space-y-3">
        <label className="flex flex-col gap-1 text-sm">
          도착 객체 유형
          <select
            required
            className="rounded-lg border border-slate-300 p-2"
            value={typeId}
            disabled={!types.isSuccess || !types.data.length}
            onChange={(event) => {
              setTypeId(event.target.value)
              setFilters(null)
              onChange(null)
            }}
          >
            <option value="">선택하세요</option>
            {types.data?.map((type) => (
              <option key={type.id} value={type.id}>
                {type.name}
              </option>
            ))}
          </select>
        </label>
        <label className="flex flex-col gap-1 text-sm">
          도착 객체 검색어
          <input
            className="rounded-lg border border-slate-300 p-2"
            placeholder="이름 또는 코드로 검색"
            value={text}
            onChange={(event) => {
              setText(event.target.value)
              onChange(null)
            }}
          />
        </label>
        <Button type="submit" disabled={!typeId || !types.isSuccess}>
          도착 객체 검색
        </Button>
      </form>
      {current && results.isFetching && (
        <LoadingState message="도착 객체를 검색하는 중…" />
      )}
      {current && results.isError && (
        <ErrorState
          error={results.error}
          onRetry={() => void results.refetch()}
        />
      )}
      {current && results.isSuccess && !results.isFetching && (
        <section aria-label="도착 객체 검색 결과" className="space-y-2">
          {results.data.data.length === 0 ? (
            <EmptyState message="조건에 맞는 도착 객체가 없습니다." />
          ) : (
            <ul className="space-y-2">
              {results.data.data.map((object) => {
                const selectable =
                  object.status === 'ACTIVE' &&
                  object.id !== sourceObjectId &&
                  object.objectTypeId === filters.objectTypeId
                return (
                  <li key={object.id}>
                    <label className="flex items-start gap-2 rounded-lg border border-slate-200 p-3 text-sm">
                      <input
                        type="radio"
                        name="manual-link-target"
                        checked={value?.id === object.id}
                        disabled={!selectable}
                        onChange={() => onChange(object)}
                      />
                      <span>
                        {object.displayName} · {object.objectTypeName} · #
                        {object.id}
                        {!selectable && ' (선택 불가)'}
                      </span>
                    </label>
                  </li>
                )
              })}
            </ul>
          )}
          {results.data.meta && (
            <Pagination
              page={page}
              size={results.data.meta.size}
              totalElements={results.data.meta.totalElements}
              onChange={(next) => {
                setPage(next)
                onChange(null)
              }}
            />
          )}
        </section>
      )}
      {value && (
        <p role="status" className="text-sm">
          선택한 도착 객체: {value.displayName} · {value.objectTypeName} · #
          {value.id}
        </p>
      )}
    </div>
  )
}
