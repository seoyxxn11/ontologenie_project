import { formatKstDateTime } from '../../utils/dateTime'
import { useQuery } from '@tanstack/react-query'
import { useEffect, useRef, useState } from 'react'
import { ApiError, getHttpErrorMessage } from '../../api/errors'
import Button from '../../components/Button'
import DataTable from '../../components/DataTable'
import { EmptyState, ErrorState, LoadingState } from '../../components/Feedback'
import { fetchAllResources } from './api'
import type {
  SampleCell,
  SamplePreview,
  SampleReceipt,
  SourceSampleAccess,
} from './samplePreview'

const active = (status?: string) => status === 'QUEUED' || status === 'RUNNING'

function safeError(error: unknown) {
  // 원천 오류에 행 값이나 SQL이 포함돼도 화면에 노출하지 않는다.
  return error instanceof ApiError
    ? new ApiError(getHttpErrorMessage(error.status ?? 500), {
        kind: error.kind,
        status: error.status ?? undefined,
      })
    : new Error('표본 조회를 처리하지 못했습니다. 연결 상태를 확인해 주세요.')
}

function cellText(cell: SampleCell | undefined) {
  if (cell?.kind === 'masked') return '가림'
  if (cell?.kind !== 'visible') return '제공 안 됨'
  if (cell.value === null) return 'NULL'
  if (cell.value === '') return '빈 문자열'
  return String(cell.value)
}

function SampleRequest({
  sourceId,
  resourceId,
  access,
}: {
  sourceId: number
  resourceId: number
  access?: SourceSampleAccess
}) {
  const [limit, setLimit] = useState('20')
  const [receipt, setReceipt] = useState<SampleReceipt>()
  const [preview, setPreview] = useState<SamplePreview>()
  const [error, setError] = useState<Error>()
  const [pending, setPending] = useState(false)
  const [expired, setExpired] = useState(false)
  const [attempt, setAttempt] = useState(0)
  const locked = useRef(false)
  const validLimit =
    /^\d+$/.test(limit) && Number(limit) >= 1 && Number(limit) <= 20
  const enabled = !!access?.policyDescription.trim()

  useEffect(() => {
    if (!access || !receipt || expired) return
    const api = access
    const previewId = receipt.previewId
    let cancelled = false
    let poll: ReturnType<typeof setTimeout>
    const remaining = new Date(receipt.expiresAt).getTime() - Date.now()
    const expiry = setTimeout(
      () => {
        cancelled = true
        clearTimeout(poll)
        locked.current = false
        setExpired(true)
        setPreview(undefined)
        setError(undefined)
      },
      Math.max(0, Number.isFinite(remaining) ? remaining : 0),
    )
    async function read() {
      try {
        const next = await api.read(previewId, resourceId)
        if (cancelled) return
        setPreview(next)
        setError(undefined)
        if (active(next.status))
          poll = setTimeout(() => {
            void read()
          }, 2000)
        else locked.current = false
      } catch (cause) {
        if (cancelled) return
        setPreview(undefined)
        setError(safeError(cause))
        if (cause instanceof ApiError && cause.kind === 'not-found')
          locked.current = false
        // 상태를 확인하지 못한 작업을 중복 접수하지 않는다.
      }
    }
    void read()
    return () => {
      cancelled = true
      clearTimeout(poll)
      clearTimeout(expiry)
    }
  }, [access, receipt, expired, attempt, resourceId])

  async function request() {
    if (!access || !enabled || !validLimit || locked.current) return
    locked.current = true
    setPending(true)
    setReceipt(undefined)
    setPreview(undefined)
    setError(undefined)
    setExpired(false)
    try {
      setReceipt(await access.request(sourceId, resourceId, Number(limit)))
    } catch (cause) {
      locked.current = false
      setError(safeError(cause))
    } finally {
      setPending(false)
    }
  }

  const notFound = error instanceof ApiError && error.kind === 'not-found'
  const busy =
    pending ||
    (!!receipt && !expired && !notFound && (!preview || active(preview.status)))
  return (
    <div className="space-y-3">
      <form
        className="flex flex-wrap items-end gap-3"
        onSubmit={(event) => {
          event.preventDefault()
          void request()
        }}
      >
        <label className="grid gap-1 text-sm text-slate-700">
          표본 행 수 (1~20)
          <input
            type="number"
            min={1}
            max={20}
            step={1}
            value={limit}
            disabled={busy}
            aria-invalid={!validLimit}
            className="w-32 rounded-lg border border-slate-300 px-3 py-2 disabled:bg-slate-100"
            onChange={(event) => setLimit(event.target.value)}
          />
        </label>
        <Button
          type="submit"
          disabled={!enabled || !validLimit || busy}
          pending={pending}
        >
          표본 조회 요청
        </Button>
      </form>
      {!validLimit && (
        <p role="alert">행 수는 1~20 사이의 정수로 입력해 주세요.</p>
      )}
      {access && enabled && (
        <p className="text-sm text-slate-600">
          표시 정책: {access.policyDescription}
        </p>
      )}
      {pending && <LoadingState message="표본 조회를 요청하는 중…" />}
      {receipt && (
        <p className="text-sm">
          표본 요청 #{receipt.previewId} · 만료{' '}
          {formatKstDateTime(receipt.expiresAt)}
          {receipt.joined ? ' · 기존 진행 중인 요청을 확인합니다.' : ''}
        </p>
      )}
      {expired || notFound ? (
        <EmptyState message="표본이 만료되었거나 존재하지 않습니다. 다시 요청해 주세요." />
      ) : error ? (
        <ErrorState
          error={error}
          onRetry={
            receipt
              ? () => {
                  setError(undefined)
                  setAttempt((value) => value + 1)
                }
              : undefined
          }
        />
      ) : receipt && !preview ? (
        <LoadingState message="표본 상태를 확인하는 중…" />
      ) : (
        preview &&
        (active(preview.status) ? (
          <LoadingState
            message={
              preview.status === 'QUEUED'
                ? '표본 조회 대기 중…'
                : '표본 조회 실행 중…'
            }
          />
        ) : preview.status === 'FAILED' ? (
          <p role="alert">
            표본을 읽지 못했습니다. 원천 접근 권한과 연결 상태를 확인한 뒤 다시
            요청해 주세요.
          </p>
        ) : (
          <section aria-label="표본 조회 결과" className="space-y-3">
            <p>
              조회 완료 · 표본 {preview.rows.length}행 · 전체 데이터가 아닌 일부
              표본입니다.
            </p>
            <p className="text-sm text-slate-500">
              가림은 숨긴 값, 제공 안 됨은 표시할 수 없는 값입니다.
            </p>
            {!preview.rows.length ? (
              <EmptyState message="조회된 표본 행이 없습니다." />
            ) : !preview.columns.length ? (
              <EmptyState message="표시할 컬럼이 없습니다." />
            ) : (
              <DataTable label="원천 행 표본">
                <thead className="bg-slate-50 text-xs text-slate-500">
                  <tr>
                    {preview.columns.map((column, index) => (
                      <th className="px-4 py-3" scope="col" key={index}>
                        {column}
                      </th>
                    ))}
                  </tr>
                </thead>
                <tbody>
                  {preview.rows.map((row, index) => (
                    <tr key={index}>
                      {preview.columns.map((_, column) => (
                        <td
                          className="max-w-80 whitespace-pre-wrap break-words px-4 py-3"
                          key={column}
                        >
                          {cellText(row[column])}
                        </td>
                      ))}
                    </tr>
                  ))}
                </tbody>
              </DataTable>
            )}
          </section>
        ))
      )}
    </div>
  )
}

export default function SourceSamples({
  sourceId,
  access,
}: {
  sourceId: number
  access?: SourceSampleAccess
}) {
  const [selected, setSelected] = useState('')
  const resources = useQuery({
    queryKey: ['sources', sourceId, 'resources', 'all'],
    queryFn: () => fetchAllResources(sourceId),
  })
  const target = resources.data?.find(
    (resource) =>
      String(resource.id) === selected &&
      resource.isReadable &&
      resource.resourceKind === 'TABLE',
  )
  return (
    <section aria-label="원천 표본 조회" className="space-y-4">
      <h3 className="font-semibold">원천 표본 조회</h3>
      <p className="text-sm text-slate-600">
        읽기 가능한 테이블에서 최대 20행을 확인합니다.
      </p>
      {!access?.policyDescription.trim() && (
        <p role="status" className="rounded-lg bg-amber-50 p-4 text-sm">
          표본 조회는 준비 중입니다. 조회 기능과 민감값 표시 정책 확정 후 사용할
          수 있습니다.
        </p>
      )}
      {resources.isPending ? (
        <LoadingState message="원천 자원을 불러오는 중…" />
      ) : resources.isError ? (
        <ErrorState
          error={resources.error}
          onRetry={() => {
            void resources.refetch()
          }}
        />
      ) : !resources.data.length ? (
        <EmptyState message="조회할 원천 자원이 없습니다." />
      ) : (
        <>
          <label className="grid max-w-xl gap-1 text-sm text-slate-700">
            표본 조회 대상
            <select
              className="w-full rounded-lg border border-slate-300 bg-white px-3 py-2"
              value={target ? selected : ''}
              onChange={(event) => setSelected(event.target.value)}
            >
              <option value="">테이블을 선택하세요</option>
              {resources.data.map((resource) => (
                <option
                  key={resource.id}
                  value={resource.id}
                  disabled={
                    !resource.isReadable || resource.resourceKind !== 'TABLE'
                  }
                >
                  {resource.resourceKey}
                  {!resource.isReadable
                    ? ' (읽기 불가)'
                    : resource.resourceKind !== 'TABLE'
                      ? ' (테이블 아님)'
                      : ''}
                </option>
              ))}
            </select>
          </label>
          {target ? (
            <SampleRequest
              key={`${sourceId}-${target.id}-${access?.policyDescription ?? ''}`}
              sourceId={sourceId}
              resourceId={target.id}
              access={access}
            />
          ) : (
            <EmptyState message="표본을 조회할 테이블을 선택해 주세요." />
          )}
        </>
      )}
    </section>
  )
}
