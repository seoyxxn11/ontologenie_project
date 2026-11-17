import { useQuery } from '@tanstack/react-query'
import { useState } from 'react'
import { formatKstDateTime } from '../../utils/dateTime'
import Button from '../../components/Button'
import Dialog from '../../components/Dialog'
import { ErrorState, LoadingState } from '../../components/Feedback'
import StatusDot from '../../components/ui/status-dot'
import { fetchExecutionChanges, fetchExecutionDetail } from './api'
import EntityChangeTable from './EntityChangeTable'
import './executionDetail.css'
import { summarizeExecutionValues, targetIdKey } from './executionSummary'
import ExecutionValues from './ExecutionValues'
import {
  eventKindLabels,
  executionStatusLabels,
  executionTones,
  targetText,
} from './labels'
import type {
  Execution,
  ExecutionDetail as Detail,
  ExecutionStatus,
} from './types'
import { valueLabels } from './valueLabels'

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

function executionName(execution: Execution) {
  return execution.actionName || eventKindLabels[execution.eventKind]
}

function actorText(execution: Execution) {
  if (execution.actorType === 'SYSTEM') return '시스템'
  return (
    execution.actorName ??
    (execution.actorId ? '사용자 #' + execution.actorId : '실행자 미제공')
  )
}

// 대상 번호. 입력값에서 뺀 번호 항목과 같은 이름으로 보여 준다.
function targetNumberText(execution: Execution) {
  if (!execution.targetType || execution.targetId == null) return null
  const key = targetIdKey(execution.targetType)
  return key
    ? `${valueLabels[key]} ${execution.targetId}`
    : targetText(execution)
}

// 실패 기록의 결과에 담긴 내부 오류 문구는 실패 이유와 겹치므로 뺀다.
function resultValuesOf(detail: Detail) {
  const values = { ...detail.result }
  if (detail.status === 'FAILED') {
    delete values.errorMessage
    delete values.reason
  }
  return values
}

// restrictedPropertyCodes는 입력값과 성공한 실행의 처리 결과에서 가린 속성을 함께 담는다(404).
// 실패·차단 기록의 결과는 서버가 가리지 않는 고정 내용이라 가림 표시를 붙이지 않는다.
function resultRestrictedCodes(detail: Detail) {
  return detail.status === 'SUCCEEDED' ? detail.restrictedPropertyCodes : []
}

function ExecutionStatusDot({ status }: { status: ExecutionStatus }) {
  return (
    <StatusDot
      tone={executionTones[status]}
      label={executionStatusLabels[status]}
      showLabel
    />
  )
}

// 대상·실행자·시각·변경 건수. 목록 행 안에서 펼칠 때는 행에 이미 있는 내용을 빼고 변경 건수만 둔다.
function ExecutionInfo({
  execution,
  full,
}: {
  execution: Execution
  full: boolean
}) {
  const number = targetNumberText(execution)
  return (
    <dl className="audit-info">
      {full && (
        <>
          <div>
            <dt>대상</dt>
            <dd>
              {execution.targetLabel ?? number ?? '—'}
              {execution.targetLabel && number && (
                <span className="audit-info-number"> · {number}</span>
              )}
            </dd>
          </div>
          <div>
            <dt>실행자</dt>
            <dd>{actorText(execution)}</dd>
          </div>
          <div>
            <dt>요청 시각</dt>
            <dd className="audit-info-time">
              {formatKstDateTime(new Date(execution.requestedAt))}
            </dd>
          </div>
        </>
      )}
      <div>
        <dt>데이터 변경</dt>
        <dd>
          {execution.changeCount > 0
            ? `${execution.changeCount}건`
            : '기록된 변경 없음'}
        </dd>
      </div>
    </dl>
  )
}

function DetailSections({
  detail,
  execution,
}: {
  detail: Detail
  execution: Execution
}) {
  const { input, result } = summarizeExecutionValues(
    execution,
    detail.input,
    resultValuesOf(detail),
  )
  return (
    <>
      {detail.restrictedPropertyCodes.length > 0 && (
        <p className="audit-detail-note">
          입력값·처리 결과 중 권한으로 가린 속성이 있습니다.
        </p>
      )}
      {detail.status === 'BLOCKED' && (
        <section className="audit-reason audit-reason--blocked">
          <h3 className="audit-detail-heading">차단 이유</h3>
          <p>{detail.blockedReason ?? '기록된 이유가 없습니다.'}</p>
        </section>
      )}
      {detail.status === 'FAILED' && (
        <section className="audit-reason">
          <h3 className="audit-detail-heading">실패 이유</h3>
          <p>{failureReasonText(detail.failureReason)}</p>
        </section>
      )}
      {detail.status === 'RUNNING' && (
        <p role="status">진행 중입니다. 완료된 처리 결과가 아직 없습니다.</p>
      )}
      {Object.keys(result).length > 0 && (
        <section className="audit-detail-section">
          <h3 className="audit-detail-heading">처리 결과</h3>
          <ExecutionValues
            values={result}
            restricted={resultRestrictedCodes(detail)}
          />
        </section>
      )}
      <section className="audit-detail-section">
        <h3 className="audit-detail-heading">입력한 내용</h3>
        <ExecutionValues
          values={input}
          restricted={detail.restrictedPropertyCodes}
        />
      </section>
    </>
  )
}

// 중복·내부 값을 빼지 않은 입력·결과. 실패 기록의 내부 오류 문구만 실패 이유와 겹쳐서 뺀다.
function TechnicalInfo({ detail }: { detail: Detail }) {
  const result = formatJson(
    resultValuesOf(detail),
    resultRestrictedCodes(detail),
  )
  return (
    <details>
      <summary>기술 정보 · JSON</summary>
      <p className="mt-2 font-semibold">입력 JSON</p>
      <pre>
        {formatJson(detail.input, detail.restrictedPropertyCodes) ??
          '기록된 입력값 없음'}
      </pre>
      {result && (
        <>
          <p className="mt-2 font-semibold">결과 JSON</p>
          <pre>{result}</pre>
        </>
      )}
    </details>
  )
}

function ExecutionChanges({ id, count }: { id: number; count: number }) {
  const [open, setOpen] = useState(false)
  const { data, isPending, isError, error, refetch } = useQuery({
    queryKey: ['audit', 'actionChanges', id],
    queryFn: ({ signal }) => fetchExecutionChanges(id, { signal }),
    enabled: open && count > 0,
  })

  if (count === 0) return null

  return (
    <details onToggle={(event) => setOpen(event.currentTarget.open)}>
      <summary>{`실행 전후 비교 ${count}건`}</summary>
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
  const status = (
    <ExecutionStatusDot status={data?.data.status ?? execution.status} />
  )
  const body = open && (
    <div className="audit-detail-body">
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
        <DetailSections detail={data.data} execution={execution} />
      )}
      <ExecutionChanges id={execution.id} count={execution.changeCount} />
      {data && <TechnicalInfo detail={data.data} />}
    </div>
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
          <Dialog
            title={executionName(execution)}
            titleAddon={status}
            size="wide"
            onClose={() => setOpen(false)}
          >
            <div className="audit-dialog-content management-detail">
              <div className="audit-detail audit-detail--wide">
                <section className="audit-detail-info">
                  <h3 className="audit-detail-heading">실행 정보</h3>
                  <ExecutionInfo execution={execution} full />
                </section>
                {body}
              </div>
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
      {open && (
        <div className="audit-detail">
          <section className="audit-detail-info">
            <p className="audit-detail-title">
              {executionName(execution)}
              {status}
            </p>
            <ExecutionInfo execution={execution} full={false} />
          </section>
          {body}
        </div>
      )}
    </details>
  )
}
