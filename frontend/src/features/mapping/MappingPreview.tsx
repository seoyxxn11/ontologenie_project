import { formatKstDateTime } from '../../utils/dateTime'
import { useEffect, useRef, useState } from 'react'
import { ApiError, getHttpErrorMessage } from '../../api/errors'
import Button from '../../components/Button'
import DataTable from '../../components/DataTable'
import { EmptyState, ErrorState, LoadingState } from '../../components/Feedback'
import type { SampleCell } from '../sources/samplePreview'
import {
  PreviewDraftError,
  type MappingPreviewAccess,
  type MappingPreviewDraft,
  type MappingPreviewReceipt,
  type MappingPreviewResult,
} from './previewModel'

const active = (status: string) => status === 'QUEUED' || status === 'RUNNING'
function safeError(error: unknown) {
  if (error instanceof PreviewDraftError) return error
  if (error instanceof ApiError && error.kind === 'invalid-response')
    return new ApiError('변환 미리보기 응답을 확인할 수 없습니다.', {
      kind: error.kind,
    })
  return error instanceof ApiError
    ? new ApiError(getHttpErrorMessage(error.status ?? 500), {
        kind: error.kind,
        status: error.status ?? undefined,
      })
    : new Error(
        '변환 미리보기를 처리하지 못했습니다. 연결 상태를 확인해 주세요.',
      )
}
function value(cell: SampleCell) {
  if (cell.kind === 'masked') return '가림'
  if (cell.kind !== 'visible') return '미제공'
  if (cell.value === null) return 'NULL'
  return cell.value === '' ? '빈 문자열' : String(cell.value)
}

export default function MappingPreview({
  access,
  prepareDraft,
  disabled = false,
}: {
  access?: MappingPreviewAccess
  prepareDraft: () => Promise<MappingPreviewDraft>
  disabled?: boolean
}) {
  const [limit, setLimit] = useState('20')
  const [receipt, setReceipt] = useState<MappingPreviewReceipt>()
  const [result, setResult] = useState<MappingPreviewResult>()
  const [error, setError] = useState<Error>()
  const [pending, setPending] = useState(false)
  const [expired, setExpired] = useState(false)
  const [attempt, setAttempt] = useState(0)
  const lock = useRef(false)
  const mounted = useRef(false)
  useEffect(() => {
    mounted.current = true
    return () => {
      mounted.current = false
    }
  }, [])
  const ready = !!access?.policyDescription.trim()
  const validLimit =
    /^\d+$/.test(limit) && Number(limit) >= 1 && Number(limit) <= 20

  useEffect(() => {
    if (!access || !receipt || expired || disabled) return
    const api = access
    const id = receipt.previewId
    let cancelled = false
    let poll: ReturnType<typeof setTimeout>
    const remaining = Date.parse(receipt.expiresAt) - Date.now()
    const expiry = setTimeout(
      () => {
        cancelled = true
        clearTimeout(poll)
        lock.current = false
        setExpired(true)
        setResult(undefined)
        setError(undefined)
      },
      Number.isFinite(remaining) ? Math.max(0, remaining) : 0,
    )
    async function read() {
      try {
        const next = await api.read(id)
        if (cancelled) return
        setResult(next)
        setError(undefined)
        if (active(next.status))
          poll = setTimeout(() => {
            void read()
          }, 2000)
        else lock.current = false
      } catch (cause) {
        if (cancelled) return
        setResult(undefined)
        setError(safeError(cause))
        if (cause instanceof ApiError && cause.kind === 'not-found')
          lock.current = false
      }
    }
    if (Number.isFinite(remaining) && remaining > 0) void read()
    return () => {
      cancelled = true
      clearTimeout(poll)
      clearTimeout(expiry)
    }
  }, [access, receipt, expired, attempt, disabled])

  async function request() {
    if (!access || !ready || !validLimit || disabled || lock.current) return
    lock.current = true
    setPending(true)
    setReceipt(undefined)
    setResult(undefined)
    setError(undefined)
    setExpired(false)
    try {
      const draft = await prepareDraft()
      if (!mounted.current) return
      const accepted = await access.request(draft, Number(limit))
      if (mounted.current) setReceipt(accepted)
    } catch (cause) {
      if (mounted.current) {
        lock.current = false
        setError(safeError(cause))
      }
    } finally {
      if (mounted.current) setPending(false)
    }
  }
  const missing =
    !!receipt && error instanceof ApiError && error.kind === 'not-found'
  const busy =
    pending ||
    (!!receipt && !expired && !missing && (!result || active(result.status)))
  return (
    <section
      aria-label="변환 미리보기"
      className="space-y-3 rounded-lg border border-slate-200 bg-slate-50 p-4"
    >
      <h3 className="font-semibold">변환 미리보기</h3>
      <p className="text-sm text-slate-600">
        저장 전 초안으로 표본을 시험 변환합니다. 객체나 매핑을 저장·반영하지
        않습니다. 초안을 수정하면 이전 결과는 닫힙니다.
      </p>
      {!ready && (
        <p role="status" className="rounded bg-amber-50 p-3">
          변환 미리보기는 준비 중입니다. 조회 기능과 민감값 표시 정책 확정 후
          사용할 수 있습니다.
        </p>
      )}
      {ready && access && <p>표시 정책: {access.policyDescription}</p>}
      <div className="flex flex-wrap items-end gap-3">
        <label className="grid gap-1">
          미리보기 행 수 (1~20)
          <input
            className="w-32 rounded border border-slate-300 bg-white px-3 py-2"
            type="number"
            min={1}
            max={20}
            step={1}
            value={limit}
            aria-invalid={!validLimit}
            disabled={busy || disabled}
            onChange={(event) => setLimit(event.target.value)}
            onKeyDown={(event) => {
              if (event.key === 'Enter') event.preventDefault()
            }}
          />
        </label>
        <Button
          variant="secondary"
          disabled={!ready || !validLimit || busy || disabled}
          pending={pending}
          onClick={() => {
            void request()
          }}
        >
          변환 미리보기 요청
        </Button>
      </div>
      {!validLimit && (
        <p role="alert">행 수는 1~20 사이의 정수로 입력해 주세요.</p>
      )}
      {pending && (
        <LoadingState message="초안을 확인하고 미리보기를 요청하는 중…" />
      )}
      {receipt && (
        <p>
          미리보기 #{receipt.previewId} · 만료{' '}
          {formatKstDateTime(receipt.expiresAt)}
          {receipt.joined ? ' · 기존 진행 중인 요청을 확인합니다.' : ''}
        </p>
      )}
      {expired || missing ? (
        <EmptyState message="미리보기가 만료되었거나 존재하지 않습니다. 다시 요청해 주세요." />
      ) : error ? (
        <ErrorState
          error={error}
          onRetry={
            receipt && !disabled
              ? () => {
                  setError(undefined)
                  setAttempt((n) => n + 1)
                }
              : undefined
          }
        />
      ) : receipt && !result ? (
        <LoadingState message="미리보기 상태를 확인하는 중…" />
      ) : (
        result &&
        !disabled &&
        (active(result.status) ? (
          <LoadingState
            message={
              result.status === 'QUEUED'
                ? '변환 미리보기 대기 중…'
                : '변환 미리보기 실행 중…'
            }
          />
        ) : result.status === 'FAILED' ? (
          <p role="alert">
            시험 변환 작업에 실패했습니다. 원천 연결과 매핑 초안을 확인한 뒤
            다시 요청해 주세요.
          </p>
        ) : (
          <div className="space-y-3">
            <p>
              시험 변환 완료 · 변환된 객체 {result.rows.length}건 · 행별 오류{' '}
              {result.rowErrors.length}건
            </p>
            <p className="text-slate-600">
              전체 데이터 검증이나 저장 완료를 의미하지 않습니다. 가림·미제공
              값은 원문을 표시하지 않습니다.
            </p>
            {!result.rows.length ? (
              <EmptyState message="변환된 객체가 없습니다." />
            ) : (
              <div className="space-y-3">
                {result.rows.map((row, index) => (
                  <section
                    key={index}
                    aria-label={
                      row.rowNumber === null
                        ? `변환 객체 ${index + 1}`
                        : `표본 ${row.rowNumber}행 변환 결과`
                    }
                    className="space-y-2"
                  >
                    <h4 className="font-medium">
                      {row.rowNumber === null
                        ? `변환 객체 ${index + 1} · 원천 행 번호 미제공`
                        : `표본 ${row.rowNumber}행`}{' '}
                      · 객체 키 {value(row.objectKey)}
                    </h4>
                    {!row.values.length ? (
                      <EmptyState message="제공된 속성 변환 결과가 없습니다." />
                    ) : (
                      <DataTable
                        label={
                          row.rowNumber === null
                            ? `변환 객체 ${index + 1} 속성 결과`
                            : `표본 ${row.rowNumber}행 변환 전후`
                        }
                      >
                        <thead>
                          <tr>
                            {[
                              '원천 컬럼·경로',
                              '대상 속성',
                              '변환 전',
                              '변환 후',
                            ].map((label) => (
                              <th scope="col" key={label} className="px-3 py-2">
                                {label}
                              </th>
                            ))}
                          </tr>
                        </thead>
                        <tbody>
                          {row.values.map((cell, i) => (
                            <tr key={i}>
                              <td className="px-3 py-2">{cell.source}</td>
                              <td className="px-3 py-2">{cell.property}</td>
                              <td className="max-w-64 whitespace-pre-wrap break-words px-3 py-2">
                                {value(cell.before)}
                              </td>
                              <td className="max-w-64 whitespace-pre-wrap break-words px-3 py-2">
                                {value(cell.after)}
                              </td>
                            </tr>
                          ))}
                        </tbody>
                      </DataTable>
                    )}
                  </section>
                ))}
              </div>
            )}
            <h4 className="font-medium">행별 변환 오류</h4>
            {!result.rowErrors.length ? (
              <EmptyState message="제공된 행별 변환 오류가 없습니다." />
            ) : (
              <ul className="space-y-2">
                {result.rowErrors.map((item, index) => (
                  <li
                    key={index}
                    className="break-words rounded border border-red-200 bg-red-50 p-3 text-red-900"
                  >
                    <p>
                      표본 {item.rowNumber}행 · 대상 속성{' '}
                      {item.property ?? '미제공'} · 변환 단계{' '}
                      {item.step ?? '미제공'}
                    </p>
                    {item.source && <p>원천 컬럼: {item.source}</p>}
                    {item.transform && (
                      <p>실패한 변환 함수: {item.transform}</p>
                    )}
                    <p>
                      오류 코드: {item.code ?? '미제공'} · 실패 근거:{' '}
                      {item.reason}
                    </p>
                    <p>변환 전: {value(item.before)}</p>
                  </li>
                ))}
              </ul>
            )}
          </div>
        ))
      )}
    </section>
  )
}
