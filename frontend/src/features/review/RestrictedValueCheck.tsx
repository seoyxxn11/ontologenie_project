import { useQuery } from '@tanstack/react-query'
import { useEffect, useRef, useState } from 'react'
import { ErrorState, LoadingState } from '../../components/Feedback'
import {
  fetchRestrictedColumns,
  readRestrictedValues,
  requestRestrictedValues,
  type RestrictedColumn,
  type RestrictedValueResult,
} from './restrictedValuesApi'

// 원천은 배치가 읽으므로 잠깐 기다렸다가 결과를 다시 묻는다.
export const RESTRICTED_VALUE_POLL_MS = 1000
const MAX_POLLS = 30

type Shown =
  | { state: 'idle' }
  | { state: 'waiting' }
  | { state: 'error'; error: Error }
  | {
      state: 'done'
      result: Extract<RestrictedValueResult, { status: 'SUCCEEDED' }>
    }
  | { state: 'failed'; message: string }

function ColumnCheck({
  proposalId,
  column,
}: {
  proposalId: number
  column: RestrictedColumn
}) {
  const [shown, setShown] = useState<Shown>({ state: 'idle' })
  const alive = useRef(true)
  useEffect(() => {
    alive.current = true
    return () => {
      alive.current = false
    }
  }, [])

  async function check() {
    setShown({ state: 'waiting' })
    try {
      const requestId = await requestRestrictedValues(proposalId, column)
      for (let poll = 0; poll < MAX_POLLS && alive.current; poll++) {
        const result = await readRestrictedValues(proposalId, requestId)
        if (!alive.current) return
        if (result.status === 'SUCCEEDED') {
          setShown({ state: 'done', result })
          return
        }
        if (result.status === 'FAILED') {
          setShown({ state: 'failed', message: result.errorMessage })
          return
        }
        await new Promise((resolve) =>
          setTimeout(resolve, RESTRICTED_VALUE_POLL_MS),
        )
      }
      if (alive.current)
        setShown({
          state: 'failed',
          message:
            '원천 값을 읽는 데 시간이 오래 걸립니다. 잠시 뒤 다시 확인해 주세요.',
        })
    } catch (error) {
      if (alive.current)
        setShown({
          state: 'error',
          error: error instanceof Error ? error : new Error(String(error)),
        })
    }
  }

  const label = `${column.resourceKey}.${column.column}`
  return (
    <li className="space-y-2 rounded-lg border border-slate-200 p-3">
      <div className="flex flex-wrap items-center justify-between gap-2">
        <span>
          <span className="font-mono">{label}</span>
          {column.sourceName && (
            <span className="ml-2 text-xs text-slate-500">
              {column.sourceName}
            </span>
          )}
        </span>
        <button
          type="button"
          className="rounded border border-slate-300 px-3 py-1 text-sm"
          disabled={shown.state === 'waiting'}
          onClick={() => void check()}
        >
          {shown.state === 'done' ? '다시 확인' : '실제 값 확인'}
        </button>
      </div>
      {shown.state === 'waiting' && (
        <LoadingState message="원천에서 값을 읽는 중…" />
      )}
      {shown.state === 'error' && (
        <ErrorState error={shown.error} title="값을 확인하지 못했습니다" />
      )}
      {shown.state === 'failed' && (
        <p role="alert" className="text-sm text-red-700">
          {shown.message}
        </p>
      )}
      {shown.state === 'done' && (
        <div aria-label={`${label} 실제 값`} className="space-y-1 text-sm">
          {shown.result.values.length ? (
            <ul className="flex flex-wrap gap-2">
              {shown.result.values.map((value) => (
                <li
                  key={value}
                  className="rounded bg-slate-100 px-2 py-0.5 font-mono"
                >
                  {value}
                </li>
              ))}
            </ul>
          ) : (
            <p>값이 모두 비어 있습니다.</p>
          )}
          <p className="text-slate-600">
            전체 {shown.result.totalCount}행 · 빈 값 {shown.result.emptyCount}행
            · 서로 다른 값 {shown.result.distinctCount}개
            {shown.result.distinctCount > shown.result.values.length &&
              ` 중 ${shown.result.values.length}개`}
          </p>
        </div>
      )}
    </li>
  )
}

// 제안이 가리키는 제한(민감) 컬럼의 실제 값을 검토자가 확인한다(447). AI는 이 컬럼의 값을 받지 않고 이름·자료형·주석으로만 제안했다.
export default function RestrictedValueCheck({
  proposalId,
}: {
  proposalId: number
}) {
  const query = useQuery({
    queryKey: ['proposals', proposalId, 'restricted-columns'],
    queryFn: ({ signal }) => fetchRestrictedColumns(proposalId, signal),
  })
  if (query.isPending) return null
  if (query.isError)
    return (
      <ErrorState
        error={query.error}
        title="제한 컬럼 목록을 불러오지 못했습니다"
        onRetry={() => void query.refetch()}
      />
    )
  if (!query.data.length) return null
  return (
    <section
      aria-label="제한 컬럼 실제 값 확인"
      className="space-y-3 rounded-lg border border-amber-200 bg-amber-50 p-4 text-sm"
    >
      <h4 className="font-semibold">제한 컬럼 실제 값 확인</h4>
      <p className="text-slate-700">
        AI는 아래 컬럼의 값을 받지 않고 이름·자료형·주석만 보고 제안했습니다.
        승인 전에 실제로 어떤 값이 들어 있는지 확인할 수 있습니다. 서로 다른 값
        최대 5개와 건수만 보이며, 어느 행의 값인지는 보이지 않습니다. 확인할
        때마다 열람 기록이 남고, 값은 이 화면에만 한 번 표시됩니다.
      </p>
      <ul className="space-y-2">
        {query.data.map((column) => (
          <ColumnCheck
            key={`${column.sourceResourceId}:${column.column}`}
            proposalId={proposalId}
            column={column}
          />
        ))}
      </ul>
    </section>
  )
}
