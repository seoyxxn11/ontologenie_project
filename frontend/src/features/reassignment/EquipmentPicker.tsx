import { useQuery } from '@tanstack/react-query'
import { useState } from 'react'
import Button from '../../components/Button'
import Dialog from '../../components/Dialog'
import Pagination from '../../components/Pagination'
import { EmptyState, ErrorState, LoadingState } from '../../components/Feedback'
import { fetchObjects } from '../explorer/api'
import { formatObjectKey } from '../explorer/format'

// 유형 코드나 설비 속성을 하드코딩하지 않고 조회 권한 범위의 객체 검색을 사용한다.
export default function EquipmentPicker({
  onSelect,
  onClose,
}: {
  onSelect: (id: number) => void
  onClose: () => void
}) {
  const [text, setText] = useState('')
  const [search, setSearch] = useState<string | null>(null)
  const [page, setPage] = useState(0)
  const results = useQuery({
    queryKey: ['reassignment-equipment-search', search, page],
    queryFn: () => fetchObjects({ q: search! }, page, 10),
    enabled: search !== null,
    retry: false,
  })
  return (
    <Dialog title="기존 설비 찾기" onClose={onClose}>
      <p className="mb-4 text-sm text-slate-600">
        설비 코드·이름으로 검색한 뒤 설비 객체를 선택하세요.
      </p>
      <form
        className="plan-equipment-search"
        onSubmit={(event) => {
          event.preventDefault()
          if (!text.trim()) return
          setPage(0)
          setSearch(text.trim())
          if (search === text.trim() && page === 0) void results.refetch()
        }}
      >
        <label>
          설비 코드·이름
          <input
            type="search"
            value={text}
            onChange={(event) => setText(event.target.value)}
            required
          />
        </label>
        <Button type="submit" pending={results.isFetching}>
          찾기
        </Button>
      </form>
      {results.isFetching && <LoadingState message="설비를 찾는 중…" />}
      {results.isError && (
        <ErrorState
          error={results.error}
          onRetry={() => void results.refetch()}
        />
      )}
      {results.isSuccess && (
        <>
          {results.data.data.length === 0 ? (
            <EmptyState message="조건에 맞는 객체가 없습니다." />
          ) : (
            <ul className="plan-equipment-results">
              {results.data.data.map((object) => (
                <li key={object.id}>
                  <Button variant="text" onClick={() => onSelect(object.id)}>
                    {formatObjectKey(object)} {object.displayName} ·{' '}
                    {object.objectTypeName}
                  </Button>
                </li>
              ))}
            </ul>
          )}
          {results.data.meta && (
            <Pagination
              page={page}
              size={results.data.meta.size}
              totalElements={results.data.meta.totalElements}
              onChange={setPage}
              pending={results.isFetching}
              compact
              showSummary={false}
            />
          )}
        </>
      )}
    </Dialog>
  )
}
