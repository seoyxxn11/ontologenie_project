import Button from '../../components/Button'
import DataTable from '../../components/DataTable'
import { LoadingState, EmptyState, ErrorState } from '../../components/Feedback'
import { keepPreviousData, useQuery } from '@tanstack/react-query'
import { useState } from 'react'
import Pagination from '../../components/Pagination'
import { startOfDay, startOfNextDay } from '../audit/dateRange'
import EntityChangeTable from '../audit/EntityChangeTable'
import { actorTypeLabels, changeKindLabels } from '../audit/labels'
import { formatSourceKey } from '../explorer/format'
import {
  DEFINITION_CHANGE_PAGE_SIZE,
  fetchDefinitionChanges,
  type DefinitionChangeFilters,
} from './api'
import { definitionTableLabels } from './labels'
import type { DefinitionChange } from './types'

function tableLabel(tableName: string) {
  return definitionTableLabels[tableName] ?? tableName
}

function actorLabel(change: DefinitionChange) {
  const actor = change.actorId ? ` · 사용자 #${change.actorId}` : ''
  return `${actorTypeLabels[change.actorType]}${actor}`
}

// 044 정의 변경 이력: 객체 유형·속성·관계 유형 등을 누가 언제 어떻게 바꿨는지 보여 준다.
function DefinitionChanges() {
  const [fromDate, setFromDate] = useState('')
  const [toDate, setToDate] = useState('')
  const [tableName, setTableName] = useState('')
  const [formError, setFormError] = useState('')
  const [filters, setFilters] = useState<DefinitionChangeFilters>({})
  const [page, setPage] = useState(0)
  const {
    data: changePage,
    isPending,
    isError,
    error,
    refetch,
  } = useQuery({
    queryKey: ['ontology', 'changes', filters, page],
    queryFn: () => fetchDefinitionChanges(filters, page),
    placeholderData: keepPreviousData,
  })

  function search() {
    if (fromDate && toDate && fromDate > toDate) {
      setFormError('종료일은 시작일 이후로 선택해 주세요.')
      return
    }
    setFormError('')
    setFilters({
      tableName: tableName || undefined,
      from: fromDate ? startOfDay(fromDate) : undefined,
      to: toDate ? startOfNextDay(toDate) : undefined,
    })
    setPage(0)
  }

  function reset() {
    setFromDate('')
    setToDate('')
    setTableName('')
    setFormError('')
    setFilters({})
    setPage(0)
  }

  const totalCount = changePage?.meta?.totalElements ?? changePage?.data.length

  let content
  if (isPending) {
    content = <LoadingState message="정의 변경 이력을 불러오는 중…" />
  } else if (isError) {
    content = (
      <ErrorState
        error={error}
        onRetry={() => {
          void refetch()
        }}
      />
    )
  } else if (changePage.data.length === 0) {
    content = <EmptyState message="조건에 맞는 변경 이력이 없습니다." />
  } else {
    content = (
      <DataTable label="정의 변경 이력" className="table-fixed min-w-[640px]">
        <thead className="bg-slate-50 text-xs text-slate-500">
          <tr>
            <th className="w-[22%] px-3 py-2 font-normal">시각·실행자</th>
            <th className="w-[26%] px-3 py-2 font-normal">대상</th>
            <th className="w-[16%] px-3 py-2 font-normal">변경</th>
            <th className="px-3 py-2 font-normal">변경 전후</th>
          </tr>
        </thead>
        <tbody className="divide-y divide-slate-100 align-top">
          {changePage.data.map((change) => (
            <tr key={change.id}>
              <td className="px-3 py-3">
                <p>{new Date(change.requestedAt).toLocaleString('ko-KR')}</p>
                <p className="text-xs text-slate-500">{actorLabel(change)}</p>
              </td>
              <td className="px-3 py-3">
                <p>{tableLabel(change.tableName)}</p>
                <p className="font-semibold">
                  {formatSourceKey(change.rowKey)}
                </p>
              </td>
              <td className="px-3 py-3">
                <p className="font-semibold">
                  {changeKindLabels[change.changeKind]}
                </p>
                <p className="break-all text-xs text-slate-500">
                  {change.actionCode ?? '-'}
                </p>
              </td>
              <td className="px-3 py-3 text-xs">
                <details>
                  <summary className="cursor-pointer text-slate-500">
                    내용 보기
                  </summary>
                  <EntityChangeTable change={change} />
                </details>
              </td>
            </tr>
          ))}
        </tbody>
      </DataTable>
    )
  }

  return (
    <div>
      <form
        className="grid items-end gap-3 text-sm md:grid-cols-[1fr_1fr_1.2fr_auto_auto]"
        onSubmit={(event) => {
          event.preventDefault()
          search()
        }}
      >
        <label className="flex flex-col gap-1">
          시작일
          <input
            type="date"
            value={fromDate}
            onChange={(event) => setFromDate(event.target.value)}
            className="rounded-lg border border-slate-300 px-2 py-1"
          />
        </label>
        <label className="flex flex-col gap-1">
          종료일
          <input
            type="date"
            value={toDate}
            onChange={(event) => setToDate(event.target.value)}
            className="rounded-lg border border-slate-300 px-2 py-1"
          />
        </label>
        <label className="flex flex-col gap-1">
          대상
          <select
            value={tableName}
            onChange={(event) => setTableName(event.target.value)}
            className="rounded-lg border border-slate-300 px-2 py-1"
          >
            <option value="">전체</option>
            {Object.entries(definitionTableLabels).map(([value, label]) => (
              <option key={value} value={value}>
                {label}
              </option>
            ))}
          </select>
        </label>
        <Button
          type="submit"
          className="rounded-lg bg-slate-900 px-3 py-1.5 font-medium text-white"
        >
          검색
        </Button>
        <Button
          variant="secondary"
          onClick={reset}
          className="rounded-lg border border-slate-300 px-3 py-1.5 text-slate-700"
        >
          초기화
        </Button>
      </form>
      {formError && (
        <p role="alert" className="mt-2 text-sm text-red-600">
          {formError}
        </p>
      )}
      <p className="mt-2 text-xs text-slate-500">
        객체 유형·속성·관계 유형·원천 매핑을 직접 바꾼 기록입니다. 연결 규칙의
        개정은 규칙 개정 탭에서 확인하세요.
      </p>
      {totalCount !== undefined && (
        <p className="mt-3 text-xs text-slate-500">{`${totalCount}건`}</p>
      )}
      <div className="mt-2 text-sm">{content}</div>
      {changePage && changePage.data.length > 0 && (
        <div className="mt-4">
          <Pagination
            page={page}
            size={DEFINITION_CHANGE_PAGE_SIZE}
            totalElements={totalCount ?? 0}
            onChange={setPage}
          />
        </div>
      )}
    </div>
  )
}

export default DefinitionChanges
