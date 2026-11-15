import { useQuery } from '@tanstack/react-query'
import { useState } from 'react'
import { formatKstDateTime } from '../../utils/dateTime'
import Button from '../../components/Button'
import Dialog from '../../components/Dialog'
import { ErrorState, LoadingState } from '../../components/Feedback'
import { fetchExecutionChanges, fetchExecutionDetail } from './api'
import EntityChangeTable from './EntityChangeTable'
import ExecutionValues from './ExecutionValues'
import { eventKindLabels, executionStatusLabels } from './labels'
import type { Execution, ExecutionDetail as Detail } from './types'

function formatJson(
  value: Record<string, unknown> | null,
  restricted: string[] = [],
) {
  if (!value || Object.keys(value).length === 0) return null
  return JSON.stringify(
    value,
    (key, item) =>
      item === null && restricted.includes(key) ? '(권한으로 가림)' : item,
    2,
  )
}

function failureReasonText(reason: string | null) {
  if (!reason) return '기록된 이유가 없습니다.'
  if (reason === 'TIMEOUT') return '실행 시간이 초과되었습니다.'
  return /^[A-Z][A-Z0-9_]*$/.test(reason)
    ? `실행에 실패했습니다. (오류 코드: ${reason})`
    : reason
}

function DetailContent({
  detail,
  execution,
  compact = false,
}: {
  compact?: boolean
  detail: Detail
  execution: Execution
}) {
  const failed = detail.status === 'FAILED'
  const resultValues = { ...detail.result }
  if (failed) {
    delete resultValues.errorMessage
    delete resultValues.reason
  }
  const input = formatJson(detail.input, detail.restrictedPropertyCodes)
  // restrictedPropertyCodes는 입력값과 성공한 실행의 처리 결과에서 가린 속성을 함께 담는다(404).
  // 실패·차단 기록의 결과는 서버가 가리지 않는 고정 내용이라 가림 표시를 붙이지 않는다.
  const result = formatJson(
    resultValues,
    detail.status === 'SUCCEEDED' ? detail.restrictedPropertyCodes : [],
  )
  const inputContent = (
    <>
      {' '}
      <p className="mt-2 font-semibold text-slate-700">입력값</p>
      {detail.restrictedPropertyCodes.length > 0 && (
        <p className="text-slate-500">
          입력값·처리 결과 중 권한으로 가린 속성이 있습니다.
        </p>
      )}
      <ExecutionValues
        values={detail.input}
        restricted={detail.restrictedPropertyCodes}
      />
    </>
  )
  return (
    <>
      <p className="mt-2 font-semibold text-slate-900">
        {execution.actionName || eventKindLabels[execution.eventKind]} ·{' '}
        {executionStatusLabels[detail.status]}
      </p>
      <p className="text-sm text-slate-600">
        변경 {execution.changeCount}건
        {execution.targetLabel ? ` · 대상: ${execution.targetLabel}` : ''}
      </p>
      {compact && (
        <p className="audit-execution-meta">
          {execution.actorType === 'SYSTEM'
            ? '시스템'
            : (execution.actorName ??
              (execution.actorId
                ? '사용자 #' + execution.actorId
                : '실행자 미제공'))}{' '}
          · {formatKstDateTime(new Date(execution.requestedAt))}
        </p>
      )}
      {!compact && inputContent}
      {compact && detail.restrictedPropertyCodes.length > 0 && (
        <p className="text-slate-500">
          입력값·처리 결과 중 권한으로 가린 속성이 있습니다.
        </p>
      )}
      {detail.status === 'BLOCKED' && (
        <>
          <p className="mt-2 font-semibold text-slate-700">차단 이유</p>
          <p className="text-slate-900">
            {detail.blockedReason ?? '기록된 이유가 없습니다.'}
          </p>
        </>
      )}
      {failed && (
        <>
          <p className="mt-2 font-semibold text-slate-700">실패 이유</p>
          <p className="text-slate-900">
            {failureReasonText(detail.failureReason)}
          </p>
        </>
      )}
      {result && (
        <>
          <p className="mt-2 font-semibold text-slate-700">처리 결과</p>
          <ExecutionValues values={resultValues} />
        </>
      )}
      {compact && detail.status === 'RUNNING' && (
        <p role="status">진행 중입니다. 완료된 처리 결과가 아직 없습니다.</p>
      )}
      {compact && (
        <details className="mt-3 text-sm">
          <summary className="cursor-pointer text-slate-500">입력값</summary>
          <div className="audit-input-values">
            <ExecutionValues
              values={detail.input}
              restricted={detail.restrictedPropertyCodes}
            />
          </div>
        </details>
      )}
      <details className="mt-3 text-sm">
        <summary className="cursor-pointer text-slate-500">
          기술 정보 · JSON
        </summary>
        <p className="mt-2 font-semibold">입력 JSON</p>
        <pre className="whitespace-pre-wrap break-all">
          {input ?? '기록된 입력값 없음'}
        </pre>
        {result && (
          <>
            <p className="mt-2 font-semibold">결과 JSON</p>
            <pre className="whitespace-pre-wrap break-all">{result}</pre>
          </>
        )}
      </details>
    </>
  )
}

function ExecutionChanges({ id, count }: { id: number; count: number }) {
  const [open, setOpen] = useState(false)
  const { data, isPending, isError, error, refetch } = useQuery({
    queryKey: ['audit', 'actionChanges', id],
    queryFn: ({ signal }) => fetchExecutionChanges(id, { signal }),
    enabled: open && count > 0,
  })

  if (count === 0) {
    return <p className="mt-2 text-slate-500">변경 전후: 기록된 변경 없음</p>
  }

  return (
    <details
      className="mt-2"
      onToggle={(event) => setOpen(event.currentTarget.open)}
    >
      <summary className="cursor-pointer text-slate-500">
        {`실행 전후 비교 ${count}건`}
      </summary>
      {open &&
        (isPending ? (
          <LoadingState message="변경 내역을 불러오는 중…" />
        ) : isError ? (
          <ErrorState
            error={error}
            onRetry={() => {
              void refetch()
            }}
          />
        ) : (
          <>
            {data.data.restrictedPropertyCodes.length > 0 && (
              <p className="mt-2 text-slate-500">
                변경 내역 중 권한으로 가린 속성이 있습니다.
              </p>
            )}
            {data.data.changes.length === 0 ? (
              <p className="mt-2 text-slate-500">기록된 변경 없음</p>
            ) : (
              data.data.changes.map((change, index) => (
                <EntityChangeTable
                  key={index}
                  change={change}
                  restrictedCodes={data.data.restrictedPropertyCodes}
                />
              ))
            )}
          </>
        ))}
    </details>
  )
}

export default function ExecutionDetail({
  execution,
  modal = false,
}: {
  execution: Execution
  modal?: boolean
}) {
  const [open, setOpen] = useState(false)
  const { data, isPending, isError, error, refetch } = useQuery({
    queryKey: ['audit', 'actionDetail', execution.id],
    queryFn: ({ signal }) => fetchExecutionDetail(execution.id, { signal }),
    enabled: open,
  })
  const content = open && (
    <>
      {isPending ? (
        <LoadingState message="실행 내용을 불러오는 중…" />
      ) : isError ? (
        <ErrorState
          error={error}
          onRetry={() => {
            void refetch()
          }}
        />
      ) : (
        <DetailContent
          detail={data.data}
          execution={execution}
          compact={modal}
        />
      )}
      <ExecutionChanges id={execution.id} count={execution.changeCount} />
    </>
  )
  if (modal)
    return (
      <>
        <Button
          variant="text"
          aria-haspopup="dialog"
          onClick={() => setOpen(true)}
        >
          내용 보기
        </Button>
        {open && (
          <Dialog title="실행 내용" size="wide" onClose={() => setOpen(false)}>
            <div className="audit-dialog-content management-detail">
              {content}
            </div>
          </Dialog>
        )}
      </>
    )
  return (
    <details
      className="management-detail"
      onToggle={(event) => setOpen(event.currentTarget.open)}
    >
      <summary className="cursor-pointer text-slate-500">내용 보기</summary>
      {content}
    </details>
  )
}
