import { formatKstDateTime } from '../../utils/dateTime'
import Button from '../../components/Button'
import StatusBadge from '../../components/StatusBadge'
import DataTable from '../../components/DataTable'
import Pagination from '../../components/Pagination'
import { LoadingState, EmptyState, ErrorState } from '../../components/Feedback'
import { useQuery } from '@tanstack/react-query'
import { useState, type ReactNode } from 'react'
import {
  fetchAuditExecutions,
  fetchObjectExecutions,
  type ExecutionFilters,
} from './api'
import { startOfDay, startOfNextDay } from './dateRange'
import ExecutionDetail from './ExecutionDetail'
import {
  actorTypeLabels,
  categoryLabels,
  eventKindLabels,
  executionStatusLabels,
} from './labels'
import type {
  Execution,
  ExecutionCategory,
  ExecutionScope,
  ExecutionStatus,
} from './types'

// "분류 · 동작 이름". 수집·검사 같은 데이터 작업은 분류 대신 작업 종류(데이터 수집 등)를 쓴다.
function actionLabel(execution: Execution) {
  const kind =
    execution.category && execution.category !== 'DATA_JOB'
      ? categoryLabels[execution.category]
      : eventKindLabels[execution.eventKind]
  const name =
    execution.actionName ??
    (execution.eventKind === 'ACTION'
      ? `Action #${execution.actionDefinitionId ?? '-'}`
      : null)
  return name ? `${kind} · ${name}` : kind
}

// 표의 굵은 대상 글자. 객체 이력이면 객체 이름을, 내 이력이면 대상 종류와 번호를 쓴다.
function targetText(execution: Execution) {
  if (!execution.targetType) {
    return '-'
  }
  const kind =
    execution.targetType === 'object_instance' ? '객체' : execution.targetType
  return `${kind} #${execution.targetId}`
}

// 실행자 이름이 없으면 번호로 보여 준다. SYSTEM은 이름 없이 "시스템".
function actorLabel(execution: Execution) {
  const type = actorTypeLabels[execution.actorType]
  if (execution.actorType === 'SYSTEM') {
    return type
  }
  const name =
    execution.actorName ??
    (execution.actorId ? `사용자 #${execution.actorId}` : null)
  return name ? `${type} · ${name}` : type
}

function scopeText(
  objectId: number | undefined,
  hasTarget: boolean,
  scope?: ExecutionScope,
) {
  if (hasTarget) {
    return '조회 범위: 선택한 대상에 대한 실행 기록'
  }
  if (objectId !== undefined) {
    return '조회 범위: 관리자는 이 객체의 전체 실행, 그 밖의 역할은 본인 실행'
  }
  if (scope === 'ALL') {
    return '조회 범위: 관리자에게 허용된 전체 실행 이력'
  }
  if (scope === 'MINE') {
    return '조회 범위: 현재 계정의 실행 이력'
  }
  return '조회 범위: 권한에 따라 볼 수 있는 실행 이력'
}

// 기본 종류 선택. 전체 페이지에서는 데이터 작업을 추가로 구분한다.
const categoryTabs: { value: ExecutionCategory | ''; label: string }[] = [
  { value: '', label: '전체' },
  { value: 'OPERATION', label: categoryLabels.OPERATION },
  { value: 'ANALYSIS', label: categoryLabels.ANALYSIS },
  { value: 'CHANGE', label: categoryLabels.CHANGE },
]

// objectId가 있으면 그 객체의 이력(080), 없으면 권한에 따른 실행 이력(096)을 보여 준다.
// target이 있으면 096을 그 대상(targetType·targetId)으로 거른다. (예: 규칙 이력의 활동 기록)
type ExecutionHistoryProps = {
  objectId?: number
  targetLabel?: string
  target?: { type: string; id: number }
  navigation?: ReactNode
  fullPage?: boolean
}

function ExecutionHistory({
  objectId,
  targetLabel,
  target,
  navigation,
  fullPage = false,
}: ExecutionHistoryProps) {
  const [category, setCategory] = useState<ExecutionCategory | ''>('')
  const [fromDate, setFromDate] = useState('')
  const [toDate, setToDate] = useState('')
  const [status, setStatus] = useState<ExecutionStatus | ''>('')
  const [formError, setFormError] = useState('')
  const [filters, setFilters] = useState<ExecutionFilters>({})
  const [page, setPage] = useState(0)
  const {
    data: executionPage,
    isPending,
    isError,
    error,
    refetch,
  } = useQuery({
    queryKey:
      objectId === undefined
        ? ['audit', 'actions', target, category, filters, page]
        : ['objects', objectId, 'executions', filters, page],
    queryFn: ({ signal }) =>
      objectId === undefined
        ? fetchAuditExecutions(
            {
              ...filters,
              category: category || undefined,
              targetType: target?.type,
              targetId: target?.id,
            },
            page,
            { signal },
          )
        : fetchObjectExecutions(objectId, filters, page, { signal }),
  })

  function search() {
    if (fromDate && toDate && fromDate > toDate) {
      setFormError('종료일은 시작일 이후로 선택해 주세요.')
      return
    }
    setFormError('')
    setFilters({
      from: fromDate ? startOfDay(fromDate) : undefined,
      to: toDate ? startOfNextDay(toDate) : undefined,
      status: status || undefined,
    })
    setPage(0)
  }

  function reset() {
    setFromDate('')
    setToDate('')
    setStatus('')
    setFormError('')
    setFilters({})
    setPage(0)
  }

  let content
  if (isPending) {
    content = <LoadingState message="실행 이력을 불러오는 중…" />
  } else if (isError) {
    content = (
      <ErrorState
        error={error}
        onRetry={() => {
          void refetch()
        }}
      />
    )
  } else if (executionPage.data.length === 0) {
    content = <EmptyState message="조건에 맞는 이력이 없습니다." />
  } else {
    content = (
      <DataTable
        label="실행 이력"
        className="audit-table table-fixed min-w-[900px]"
      >
        <thead className="bg-slate-50 text-sm text-slate-500">
          <tr>
            <th className="w-[22%] px-3 py-2 font-normal">실행 시각</th>
            <th className="w-[26%] px-3 py-2 font-normal">실행자</th>
            <th className="w-[28%] px-3 py-2 font-normal">업무·대상</th>
            <th className="w-[10%] px-3 py-2 font-normal">결과</th>
            <th className="px-3 py-2 font-normal">상세</th>
          </tr>
        </thead>
        <tbody className="divide-y divide-slate-100 align-top">
          {executionPage.data.map((execution) => {
            const requestedAt = new Date(execution.requestedAt)
            return (
              <tr key={execution.id}>
                <td className="px-3 py-3">
                  <p>{formatKstDateTime(requestedAt)}</p>
                </td>
                <td className="px-3 py-3">
                  {fullPage ? (
                    <>
                      <p className="font-semibold">
                        {execution.actorType === 'SYSTEM'
                          ? '시스템'
                          : (execution.actorName ??
                            (execution.actorId
                              ? `사용자 #${execution.actorId}`
                              : actorTypeLabels[execution.actorType]))}
                      </p>
                      {execution.actorType !== 'SYSTEM' && (
                        <p className="text-sm text-slate-500">
                          {actorTypeLabels[execution.actorType]}
                        </p>
                      )}
                    </>
                  ) : (
                    <p className="text-sm text-slate-500">
                      {actorLabel(execution)}
                    </p>
                  )}
                </td>
                <td className="px-3 py-3">
                  <p>{actionLabel(execution)}</p>
                  <p className="font-semibold">
                    {targetLabel ??
                      execution.targetLabel ??
                      targetText(execution)}
                  </p>
                  {!targetLabel && execution.targetLabel && (
                    <p className="text-sm text-slate-500">
                      {targetText(execution)}
                    </p>
                  )}
                </td>
                <td className="px-3 py-3 font-semibold">
                  <StatusBadge
                    tone={
                      execution.status === 'SUCCEEDED'
                        ? 'success'
                        : execution.status === 'FAILED'
                          ? 'danger'
                          : execution.status === 'BLOCKED'
                            ? 'warning'
                            : 'info'
                    }
                  >
                    {executionStatusLabels[execution.status]}
                  </StatusBadge>
                </td>
                <td className="px-3 py-3">
                  <ExecutionDetail execution={execution} modal={fullPage} />
                </td>
              </tr>
            )
          })}
        </tbody>
      </DataTable>
    )
  }

  return (
    <div className={`management-detail ${fullPage ? 'audit-history' : ''}`}>
      <div className="audit-navigation">
        {navigation}
        {/* 종류 탭은 096 전체 목록에서만 쓴다. 객체(080)나 대상(규칙 활동 기록 등)을 고른 화면에서는 숨긴다. */}
        {objectId === undefined && target === undefined && (
          <div
            role="group"
            aria-label="실행 이력 종류"
            className="management-tabs mb-4"
          >
            {(fullPage
              ? [
                  ...categoryTabs,
                  {
                    value: 'DATA_JOB' as const,
                    label: categoryLabels.DATA_JOB,
                  },
                ]
              : categoryTabs
            ).map((tab) => (
              <button
                key={tab.value}
                type="button"
                aria-pressed={category === tab.value}
                onClick={() => {
                  setCategory(tab.value)
                  setPage(0)
                }}
              >
                {tab.label}
              </button>
            ))}
          </div>
        )}
      </div>
      <form
        className={`management-filter grid items-end gap-3 text-sm ${
          targetLabel
            ? 'md:grid-cols-[1fr_1fr_1.4fr_1fr_auto_auto]'
            : 'md:grid-cols-[1fr_1fr_1fr_auto_auto]'
        }`}
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
        {targetLabel && (
          <label className="flex flex-col gap-1">
            대상
            <input
              type="text"
              value={targetLabel}
              readOnly
              className="rounded-lg border border-slate-300 bg-slate-50 px-2 py-1"
            />
          </label>
        )}
        <label className="flex flex-col gap-1">
          결과
          <select
            value={status}
            onChange={(event) =>
              setStatus(event.target.value as ExecutionStatus | '')
            }
            className="rounded-lg border border-slate-300 px-2 py-1"
          >
            <option value="">전체</option>
            {Object.entries(executionStatusLabels).map(([value, label]) => (
              <option key={value} value={value}>
                {label}
              </option>
            ))}
          </select>
        </label>
        <Button type="submit">검색</Button>
        <Button variant="secondary" onClick={reset}>
          초기화
        </Button>
      </form>
      {formError && (
        <p role="alert" className="mt-2 text-sm text-red-600">
          {formError}
        </p>
      )}
      <div className={fullPage ? 'audit-context' : ''}>
        <p className="mt-2 text-sm text-slate-500">
          {`${scopeText(objectId, target !== undefined, executionPage?.meta?.scope)} · Agent 조회 기록은 제외됩니다.`}
        </p>
        {!fullPage && executionPage && !isError && (
          <p className="mt-3 text-sm text-slate-500">
            {`${executionPage.meta?.totalElements ?? executionPage.data.length}건`}
          </p>
        )}
      </div>
      <div className="audit-results mt-2 text-sm">{content}</div>
      {executionPage?.meta &&
        (fullPage || executionPage.meta.totalElements > 0) &&
        !isError && (
          <div className={fullPage ? 'audit-pagination' : 'mt-4'}>
            <Pagination
              page={executionPage.meta.page}
              size={executionPage.meta.size}
              totalElements={executionPage.meta.totalElements}
              onChange={setPage}
            />
          </div>
        )}
    </div>
  )
}

export default ExecutionHistory
