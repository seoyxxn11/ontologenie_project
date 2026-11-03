import { keepPreviousData, useQuery } from '@tanstack/react-query'
import { useState } from 'react'
import {
  fetchAuditExecutions,
  fetchObjectExecutions,
  type ExecutionFilters,
} from './api'
import { startOfDay, startOfNextDay } from './dateRange'
import EntityChangeTable from './EntityChangeTable'
import {
  actorTypeLabels,
  eventKindLabels,
  executionStatusLabels,
} from './labels'
import type { Execution, ExecutionScope, ExecutionStatus } from './types'

function formatJson(value: Record<string, unknown>) {
  return Object.keys(value).length > 0 ? JSON.stringify(value, null, 2) : null
}

function actionLabel(execution: Execution) {
  const kind = eventKindLabels[execution.eventKind]
  return execution.eventKind === 'ACTION'
    ? `${kind} · Action #${execution.actionDefinitionId ?? '-'}`
    : kind
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

function actorLabel(execution: Execution) {
  const actor = execution.actorId ? ` · 사용자 #${execution.actorId}` : ''
  return `${actorTypeLabels[execution.actorType]}${actor}`
}

function scopeText(objectId: number | undefined, scope?: ExecutionScope) {
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

type ExecutionDetailProps = {
  execution: Execution
}

function ExecutionDetail({ execution }: ExecutionDetailProps) {
  const input = formatJson(execution.input)
  const result = formatJson(execution.result)

  return (
    <details className="text-xs">
      <summary className="cursor-pointer text-slate-500">내용 보기</summary>
      <p className="mt-2 font-semibold text-slate-700">입력값</p>
      <pre className="whitespace-pre-wrap break-all text-slate-900">
        {input ?? '기록된 입력값 없음'}
      </pre>
      {execution.status === 'BLOCKED' && (
        <>
          <p className="mt-2 font-semibold text-slate-700">차단 이유</p>
          <p className="text-slate-900">
            {execution.blockedReason ?? '기록된 이유가 없습니다.'}
          </p>
        </>
      )}
      {result && (
        <>
          <p className="mt-2 font-semibold text-slate-700">처리 결과</p>
          <pre className="whitespace-pre-wrap break-all text-slate-900">
            {result}
          </pre>
        </>
      )}
      {execution.entityChanges.length > 0 ? (
        <details className="mt-2">
          <summary className="cursor-pointer text-slate-500">
            {`실행 전후 비교 ${execution.entityChanges.length}건`}
          </summary>
          {execution.entityChanges.map((change, index) => (
            <EntityChangeTable key={index} change={change} />
          ))}
        </details>
      ) : (
        <p className="mt-2 text-slate-500">변경 전후: 기록된 변경 없음</p>
      )}
    </details>
  )
}

// objectId가 있으면 그 객체의 이력(080), 없으면 권한에 따른 실행 이력(096)을 보여 준다.
type ExecutionHistoryProps = {
  objectId?: number
  targetLabel?: string
}

function ExecutionHistory({ objectId, targetLabel }: ExecutionHistoryProps) {
  const [fromDate, setFromDate] = useState('')
  const [toDate, setToDate] = useState('')
  const [status, setStatus] = useState<ExecutionStatus | ''>('')
  const [formError, setFormError] = useState('')
  const [filters, setFilters] = useState<ExecutionFilters>({})
  const {
    data: executionPage,
    isPending,
    isError,
    error,
  } = useQuery({
    queryKey:
      objectId === undefined
        ? ['audit', 'actions', filters]
        : ['objects', objectId, 'executions', filters],
    queryFn: () =>
      objectId === undefined
        ? fetchAuditExecutions(filters)
        : fetchObjectExecutions(objectId, filters),
    placeholderData: keepPreviousData,
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
  }

  function reset() {
    setFromDate('')
    setToDate('')
    setStatus('')
    setFormError('')
    setFilters({})
  }

  let content
  if (isPending) {
    content = <p className="text-slate-500">실행 이력을 불러오는 중…</p>
  } else if (isError) {
    content = <p className="text-red-600">{error.message}</p>
  } else if (executionPage.data.length === 0) {
    content = <p className="text-slate-500">조건에 맞는 이력이 없습니다.</p>
  } else {
    content = (
      <table className="w-full table-fixed text-left">
        <thead className="bg-slate-50 text-xs text-slate-500">
          <tr>
            <th className="w-[22%] px-3 py-2 font-normal">시각·실행자</th>
            <th className="w-[24%] px-3 py-2 font-normal">동작·대상</th>
            <th className="w-[8%] px-3 py-2 font-normal">결과</th>
            <th className="px-3 py-2 font-normal">상세</th>
          </tr>
        </thead>
        <tbody className="divide-y divide-slate-100 align-top">
          {executionPage.data.map((execution) => {
            const requestedAt = new Date(execution.requestedAt)
            return (
              <tr key={execution.id}>
                <td className="px-3 py-3">
                  <p>{requestedAt.toLocaleString('ko-KR')}</p>
                  <p className="text-xs text-slate-500">
                    {actorLabel(execution)}
                  </p>
                </td>
                <td className="px-3 py-3">
                  <p>{actionLabel(execution)}</p>
                  <p className="font-semibold">
                    {targetLabel ?? targetText(execution)}
                  </p>
                </td>
                <td className="px-3 py-3 font-semibold">
                  {executionStatusLabels[execution.status]}
                </td>
                <td className="px-3 py-3">
                  <ExecutionDetail execution={execution} />
                </td>
              </tr>
            )
          })}
        </tbody>
      </table>
    )
  }

  return (
    <div>
      <form
        className={`grid items-end gap-3 text-sm ${
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
        <button
          type="submit"
          className="rounded-lg bg-slate-900 px-3 py-1.5 font-medium text-white"
        >
          검색
        </button>
        <button
          type="button"
          onClick={reset}
          className="rounded-lg border border-slate-300 px-3 py-1.5 text-slate-700"
        >
          초기화
        </button>
      </form>
      {formError && (
        <p role="alert" className="mt-2 text-sm text-red-600">
          {formError}
        </p>
      )}
      <p className="mt-2 text-xs text-slate-500">
        {`${scopeText(objectId, executionPage?.meta?.scope)} · Agent 조회 기록은 제외됩니다.`}
      </p>
      {executionPage && (
        <p className="mt-3 text-xs text-slate-500">
          {`${executionPage.meta?.totalElements ?? executionPage.data.length}건`}
        </p>
      )}
      <div className="mt-2 text-sm">{content}</div>
    </div>
  )
}

export default ExecutionHistory
