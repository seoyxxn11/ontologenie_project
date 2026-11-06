import { useMutation, useQuery } from '@tanstack/react-query'
import { useState } from 'react'
import Button from '../../components/Button'
import DataTable from '../../components/DataTable'
import Dialog from '../../components/Dialog'
import { EmptyState, ErrorState, LoadingState } from '../../components/Feedback'
import Pagination from '../../components/Pagination'
import { fetchColumns, fetchResources, fetchSources } from '../sources/api'
import type { SourceColumn, SourceResource } from '../sources/types'
import { startAnalysis } from './api'
import type { AnalysisRun, AnalysisTarget } from './types'

const inputClass = 'w-full rounded-lg border border-slate-300 px-3 py-2'

// 목업처럼 표 목록과 컬럼 목록은 몇 개씩 나눠 보여 준다.
export const RESOURCE_PAGE_SIZE = 6
export const COLUMN_PAGE_SIZE = 8

// 고른 컬럼을 표별로 모은다. allColumns는 그 표의 전체 컬럼 수.
type Selection = Record<
  number,
  { resource: SourceResource; columns: string[]; allColumns: number }
>

function isDocument(resource: SourceResource) {
  return (
    resource.resourceKind === 'DOCUMENT_SET' || resource.resourceKind === 'FILE'
  )
}

function resourceTitle(resource: SourceResource) {
  const description = resource.sourceMetadata.description
  return description
    ? `${resource.resourceKey} · ${description}`
    : resource.resourceKey
}

function ColumnPicker({
  sourceId,
  resource,
  sourceName,
  picked,
  onChange,
}: {
  sourceId: number
  resource: SourceResource
  sourceName: string
  picked: string[]
  onChange: (columns: string[], allColumns: number) => void
}) {
  const [query, setQuery] = useState('')
  const [columnPage, setColumnPage] = useState(0)
  const {
    data: columns,
    isPending,
    isError,
    error,
    refetch,
  } = useQuery({
    queryKey: ['sources', sourceId, 'resources', resource.id, 'columns'],
    queryFn: () => fetchColumns(sourceId, resource.id),
  })

  let content
  if (isPending) {
    content = <LoadingState message="컬럼을 불러오는 중…" />
  } else if (isError) {
    content = (
      <ErrorState
        error={error}
        onRetry={() => {
          void refetch()
        }}
      />
    )
  } else {
    const present = columns.filter((column) => column.isPresent)
    const shown = present.filter((column) =>
      column.columnName.toLowerCase().includes(query.trim().toLowerCase()),
    )
    const page = Math.min(
      columnPage,
      Math.max(0, Math.ceil(shown.length / COLUMN_PAGE_SIZE) - 1),
    )
    const pageColumns = shown.slice(
      page * COLUMN_PAGE_SIZE,
      (page + 1) * COLUMN_PAGE_SIZE,
    )
    // 전체 체크는 검색 결과의 모든 페이지에 적용한다.
    const allPicked =
      shown.length > 0 &&
      shown.every((column) => picked.includes(column.columnName))
    const toggle = (column: SourceColumn) =>
      onChange(
        picked.includes(column.columnName)
          ? picked.filter((name) => name !== column.columnName)
          : [...picked, column.columnName],
        present.length,
      )
    const toggleAll = () => {
      const names = shown.map((column) => column.columnName)
      onChange(
        allPicked
          ? picked.filter((name) => !names.includes(name))
          : [...new Set([...picked, ...names])],
        present.length,
      )
    }
    content = (
      <>
        <p className="text-sm text-slate-600">
          전체 {present.length}개 중{' '}
          <strong className="text-slate-900">{picked.length}개 선택</strong>
        </p>
        <input
          value={query}
          onChange={(event) => {
            setQuery(event.target.value)
            setColumnPage(0)
          }}
          placeholder="컬럼 이름 검색"
          aria-label="컬럼 이름 검색"
          className={inputClass}
        />
        {shown.length === 0 ? (
          <EmptyState message="조건에 맞는 컬럼이 없습니다." />
        ) : (
          <DataTable label={`${resource.resourceKey} 컬럼 선택`}>
            <thead className="bg-slate-50 text-xs text-slate-500">
              <tr>
                <th className="w-16 px-3 py-2 font-normal">
                  <label className="flex flex-col items-start gap-1">
                    <input
                      type="checkbox"
                      checked={allPicked}
                      onChange={toggleAll}
                    />
                    전체
                  </label>
                </th>
                <th className="px-3 py-2 font-normal">컬럼</th>
                <th className="px-3 py-2 font-normal">형식</th>
              </tr>
            </thead>
            <tbody className="divide-y divide-slate-100">
              {pageColumns.map((column) => (
                <tr key={column.id}>
                  <td className="px-3 py-2">
                    <input
                      type="checkbox"
                      aria-label={`${column.columnName} 선택`}
                      checked={picked.includes(column.columnName)}
                      onChange={() => toggle(column)}
                    />
                  </td>
                  <td className="px-3 py-2 font-mono text-xs">
                    {column.columnName}
                    {column.isKeyPart && (
                      <span className="ml-2 font-sans text-slate-500">
                        기본키
                      </span>
                    )}
                  </td>
                  <td className="px-3 py-2 font-mono text-xs">
                    {column.sourceDataType}
                  </td>
                </tr>
              ))}
            </tbody>
          </DataTable>
        )}
        {shown.length > COLUMN_PAGE_SIZE && (
          <Pagination
            page={page}
            size={COLUMN_PAGE_SIZE}
            totalElements={shown.length}
            onChange={setColumnPage}
          />
        )}
      </>
    )
  }

  return (
    <div className="space-y-3">
      <div>
        <h3 className="font-semibold text-slate-900">
          {resourceTitle(resource)}
        </h3>
        <p className="text-xs text-slate-500">{sourceName}</p>
      </div>
      {resource.isReadable ? (
        content
      ) : (
        <p role="alert" className="text-sm text-red-700">
          수집 계정이 읽을 수 없는 표입니다. 연결을 다시 확인해 주세요.
        </p>
      )}
    </div>
  )
}

type Props = {
  initialSourceId?: number
  onClose: () => void
  onStarted: (run: AnalysisRun) => void
  // 목업처럼 창을 닫고 데이터 연결 화면의 수집 대상 탭·원천 등록으로 넘어간다.
  onEditScope: (sourceId: number) => void
  onRegister: () => void
}

// 분석 대상 선택(S15P31C106-107): 원천의 표·컬럼을 골라 018로 분석을 맡긴다.
function AnalysisTargetDialog({
  initialSourceId,
  onClose,
  onStarted,
  onEditScope,
  onRegister,
}: Props) {
  const [chosenSourceId, setChosenSourceId] = useState(initialSourceId)
  const [tableQuery, setTableQuery] = useState('')
  const [onlyPicked, setOnlyPicked] = useState(false)
  const [listPage, setListPage] = useState(0)
  const [activeId, setActiveId] = useState<number>()
  const [selection, setSelection] = useState<Selection>({})

  const sources = useQuery({
    queryKey: ['sources', 'list'],
    queryFn: fetchSources,
  })
  const sourceId = chosenSourceId ?? sources.data?.[0]?.id
  const source = sources.data?.find((item) => item.id === sourceId)

  const resources = useQuery({
    queryKey: ['sources', sourceId, 'resources'],
    queryFn: () => fetchResources(sourceId!),
    enabled: sourceId !== undefined,
  })

  const start = useMutation({
    mutationFn: () => {
      const targets: AnalysisTarget[] = Object.values(selection).map(
        ({ resource, columns, allColumns }) =>
          // 표의 컬럼을 모두 골랐으면 컬럼 목록을 빼서 표 전체를 분석한다.
          columns.length === allColumns
            ? { sourceResourceId: resource.id }
            : { sourceResourceId: resource.id, columns },
      )
      return startAnalysis(targets)
    },
    onSuccess: onStarted,
  })

  const picked = Object.values(selection)
  const summary = {
    sources: new Set(picked.map(({ resource }) => resource.dataSourceId)).size,
    tables: picked.filter(({ resource }) => !isDocument(resource)).length,
    columns: picked.reduce((sum, item) => sum + item.columns.length, 0),
    documents: picked.filter(({ resource }) => isDocument(resource)).length,
  }

  const shownResources = (resources.data ?? []).filter(
    (resource) =>
      resource.resourceKey
        .toLowerCase()
        .includes(tableQuery.trim().toLowerCase()) &&
      (!onlyPicked || selection[resource.id]),
  )
  const page = Math.min(
    listPage,
    Math.max(0, Math.ceil(shownResources.length / RESOURCE_PAGE_SIZE) - 1),
  )
  const pageResources = shownResources.slice(
    page * RESOURCE_PAGE_SIZE,
    (page + 1) * RESOURCE_PAGE_SIZE,
  )
  const active =
    resources.data?.find((resource) => resource.id === activeId) ??
    pageResources[0]

  function pickColumns(
    resource: SourceResource,
    columns: string[],
    allColumns: number,
  ) {
    setSelection((previous) => {
      const next = { ...previous }
      if (columns.length === 0) {
        delete next[resource.id]
      } else {
        next[resource.id] = { resource, columns, allColumns }
      }
      return next
    })
  }

  let resourceList
  if (sources.isError) {
    resourceList = <ErrorState error={sources.error} />
  } else if (sources.isPending) {
    resourceList = <LoadingState message="원천을 불러오는 중…" />
  } else if (sourceId === undefined) {
    // 원천이 없으면 표 조회를 하지 않아 isPending이 계속 참으로 남는다.
    resourceList = (
      <EmptyState message="등록된 원천이 없습니다. 먼저 원천을 등록해 주세요." />
    )
  } else if (resources.isPending) {
    resourceList = <LoadingState message="표를 불러오는 중…" />
  } else if (resources.isError) {
    resourceList = (
      <ErrorState
        error={resources.error}
        onRetry={() => {
          void resources.refetch()
        }}
      />
    )
  } else if (shownResources.length === 0) {
    resourceList = <EmptyState message="조건에 맞는 표가 없습니다." />
  } else {
    resourceList = (
      <ul aria-label="표·자료 목록" className="space-y-2">
        {pageResources.map((resource) => {
          const chosen = selection[resource.id]
          return (
            <li key={resource.id}>
              <button
                type="button"
                aria-pressed={resource.id === active?.id}
                onClick={() => setActiveId(resource.id)}
                className={`w-full rounded-lg border px-3 py-2 text-left ${
                  resource.id === active?.id
                    ? 'border-slate-900 bg-white'
                    : 'border-slate-200 bg-white hover:border-slate-400'
                }`}
              >
                <span className="block font-medium text-slate-900">
                  {resourceTitle(resource)}
                </span>
                <span className="text-xs text-slate-500">
                  {isDocument(resource) ? '문서·자료' : '테이블'}
                  {chosen ? ` · ${chosen.columns.length}개 컬럼 선택` : ''}
                </span>
              </button>
            </li>
          )
        })}
      </ul>
    )
  }

  return (
    <Dialog title="분석 대상 선택" size="wide" onClose={onClose}>
      <div className="mt-4 space-y-4 text-sm">
        <div className="flex flex-wrap items-end gap-3">
          <label className="flex w-full max-w-sm flex-col gap-2">
            등록된 원천
            <select
              value={sourceId ?? ''}
              onChange={(event) => {
                setChosenSourceId(Number(event.target.value))
                setActiveId(undefined)
                setListPage(0)
              }}
              className={inputClass}
            >
              {(sources.data ?? []).map((item) => (
                <option key={item.id} value={item.id}>
                  {item.name}
                </option>
              ))}
            </select>
          </label>
          <div className="ml-auto flex gap-2">
            {source && (
              <Button
                variant="secondary"
                onClick={() => onEditScope(source.id)}
              >
                이 원천의 수집 대상 변경
              </Button>
            )}
            <Button variant="secondary" onClick={onRegister}>
              + 원천 등록
            </Button>
          </div>
        </div>

        <div className="grid gap-4 md:grid-cols-[16rem_1fr]">
          <section
            aria-label="테이블·자료 찾기"
            className="space-y-3 rounded-lg bg-slate-50 p-3"
          >
            <h3 className="font-semibold text-slate-900">테이블·자료 찾기</h3>
            <input
              value={tableQuery}
              onChange={(event) => {
                setTableQuery(event.target.value)
                setListPage(0)
              }}
              placeholder="테이블·자료 이름 검색"
              aria-label="테이블·자료 이름 검색"
              className={`${inputClass} bg-white`}
            />
            <label className="flex items-center gap-2">
              <input
                type="checkbox"
                checked={onlyPicked}
                onChange={(event) => {
                  setOnlyPicked(event.target.checked)
                  setListPage(0)
                }}
              />
              이 원천에서 선택한 자료만
            </label>
            {resourceList}
            {resources.data && shownResources.length > RESOURCE_PAGE_SIZE && (
              <Pagination
                page={page}
                size={RESOURCE_PAGE_SIZE}
                totalElements={shownResources.length}
                onChange={setListPage}
              />
            )}
          </section>
          <section aria-label="컬럼 선택">
            {active && source ? (
              <ColumnPicker
                key={active.id}
                sourceId={source.id}
                resource={active}
                sourceName={source.name}
                picked={selection[active.id]?.columns ?? []}
                onChange={(columns, allColumns) =>
                  pickColumns(active, columns, allColumns)
                }
              />
            ) : (
              <EmptyState message="왼쪽에서 표를 골라 주세요." />
            )}
          </section>
        </div>

        {start.isError && <ErrorState error={start.error} />}

        <div className="flex flex-wrap items-center justify-between gap-3 border-t border-slate-200 pt-4">
          <p aria-live="polite" className="text-slate-700">
            {`${summary.sources}개 원천 · ${summary.tables}개 테이블 · ${summary.columns}개 컬럼 · ${summary.documents}개 문서·자료`}
          </p>
          <div className="flex gap-2">
            <Button variant="secondary" onClick={onClose}>
              취소
            </Button>
            <Button
              disabled={picked.length === 0}
              pending={start.isPending}
              onClick={() => start.mutate()}
            >
              분석하고 후보 받기
            </Button>
          </div>
        </div>
      </div>
    </Dialog>
  )
}

export default AnalysisTargetDialog
