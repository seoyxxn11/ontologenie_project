import { formatKstDateTime } from '../../utils/dateTime'
import { useQuery, useQueryClient } from '@tanstack/react-query'
import { useEffect, useRef, useState } from 'react'
import { useNavigate } from 'react-router'
import { ApiError } from '../../api/errors'
import Button from '../../components/Button'
import DataTable from '../../components/DataTable'
import { EmptyState, ErrorState, LoadingState } from '../../components/Feedback'
import StatusBadge from '../../components/StatusBadge'
import { fetchAnalysis, fetchRecentAnalyses } from './api'
import AnalysisTargetDialog from './AnalysisTargetDialog'
import {
  analysisCode,
  analysisStatusLabel,
  isAnalysisRunning,
  summaryNotices,
} from './labels'
import type {
  Analysis,
  AnalysisKind,
  AnalysisRun,
  AnalysisStatus,
} from './types'

// 분석이 끝날 때까지 019를 다시 부르는 간격
export const ANALYSIS_POLL_MS = 2000
// 서버·네트워크 오류일 때 한 번의 확인에서 다시 묻는 횟수
const ANALYSIS_RETRY_COUNT = 2
// 마지막으로 진행 상태를 받은 뒤 이 시간 동안 계속 실패하면 더 묻지 않고 다시 확인 버튼을 둔다
export const ANALYSIS_ERROR_LIMIT_MS = 150_000

// 받아 둔 진행 상태가 있고, 그 뒤로 서버·네트워크 실패만 이 시간 넘게 이어졌다
function failingTooLong(state: {
  dataUpdatedAt: number
  errorUpdatedAt: number
  error: Error | null
}) {
  return (
    state.dataUpdatedAt > 0 &&
    state.error !== null &&
    isTransientError(state.error) &&
    state.errorUpdatedAt - state.dataUpdatedAt >= ANALYSIS_ERROR_LIMIT_MS
  )
}

function isTransientError(error: Error) {
  return (
    error instanceof ApiError &&
    (error.kind === 'server' || error.kind === 'network')
  )
}

function formatTime(value: string | null) {
  return value ? formatKstDateTime(value) : '-'
}

function proposalCount(run: Analysis) {
  return run.resultSummary?.proposals ?? 0
}

function CurrentAnalysis({
  id,
  joined,
  onShowAll,
}: {
  id: number
  joined: boolean
  onShowAll: () => void
}) {
  const queryClient = useQueryClient()
  const query = useQuery({
    queryKey: ['analyses', id],
    queryFn: ({ signal }) => fetchAnalysis(id, signal),
    // 서버·네트워크 오류 한 번으로 진행 확인을 멈추지 않는다. 몇 번 다시 묻고, 그래도 실패하면 다음 간격에 또 묻는다.
    refetchInterval: (current) => {
      const { data, error } = current.state
      if (data && !isAnalysisRunning(data.status)) return false
      if (error && !isTransientError(error)) return false
      if (failingTooLong(current.state)) return false
      return ANALYSIS_POLL_MS
    },
    retry: (count, error) =>
      isTransientError(error) && count < ANALYSIS_RETRY_COUNT,
  })
  const status = query.data?.status
  const previousStatus = useRef<AnalysisStatus>(undefined)
  const pollingStopped = failingTooLong({
    dataUpdatedAt: query.dataUpdatedAt,
    errorUpdatedAt: query.errorUpdatedAt,
    error: query.error,
  })

  // 진행 중이던 분석이 끝나면 새 후보와 최근 분석 목록을 다시 불러온다.
  // 이미 끝난 분석을 열 때는 불러오던 목록을 다시 시작하지 않는다.
  useEffect(() => {
    const before = previousStatus.current
    previousStatus.current = status
    if (
      before !== undefined &&
      isAnalysisRunning(before) &&
      status !== undefined &&
      !isAnalysisRunning(status)
    ) {
      void queryClient.invalidateQueries({ queryKey: ['proposals'] })
      void queryClient.invalidateQueries({ queryKey: ['analyses', 'recent'] })
    }
  }, [status, queryClient])

  // 받은 결과가 있으면 그 뒤의 조회 오류는 결과 아래 안내 줄로만 보인다.
  let body
  const run = query.data
  if (run === undefined)
    body = query.isError ? (
      <ErrorState
        error={query.error}
        onRetry={() => {
          void query.refetch()
        }}
      />
    ) : (
      <LoadingState message="분석 상태를 불러오는 중…" />
    )
  else {
    body = (
      <>
        <div className="flex flex-wrap items-center gap-2">
          <StatusBadge tone={analysisStatusLabel(run.status).tone}>
            {analysisStatusLabel(run.status).label}
          </StatusBadge>
          <span className="font-medium text-slate-900">
            {run.status === 'SUCCEEDED'
              ? `이번 분석 후보 ${proposalCount(run)}건`
              : run.status === 'FAILED'
                ? '분석에 실패했습니다'
                : 'AI가 고른 범위를 분석하고 있습니다'}
          </span>
          <span className="text-slate-500">
            {`· ${analysisCode(run.id)} · ${formatTime(run.requestedAt)}`}
          </span>
        </div>
        <p className="text-slate-600">
          {`분석 대상: 자료 ${run.targets.length}개`}
          {run.resultSummary?.holdRecommended
            ? ` · 보류 권고 ${run.resultSummary.holdRecommended}건`
            : ''}
          {run.resultSummary?.documentLinks
            ? ` · 문서 연결 ${run.resultSummary.documentLinks}건`
            : ''}
        </p>
        {run.runKind === 'REANALYSIS' && (
          <section aria-label="요청한 재분석 범위" className="space-y-1">
            <h3 className="font-semibold">변경 데이터 재분석</h3>
            <ul>
              {run.targets.map((target) => (
                <li key={target.sourceResourceId}>
                  자료 #{target.sourceResourceId} ·{' '}
                  {run.analysisRange?.columns?.[
                    String(target.sourceResourceId)
                  ]?.join(', ') || '전체 컬럼·자료'}
                </li>
              ))}
            </ul>
          </section>
        )}
        {summaryNotices(run).length > 0 && (
          <ul
            aria-label="분석 범위 안내"
            className="space-y-1 rounded-lg bg-amber-50 p-3 text-amber-800"
          >
            {summaryNotices(run).map((notice) => (
              <li key={notice}>{notice}</li>
            ))}
          </ul>
        )}
        {joined && (
          <p className="text-xs text-slate-500">
            같은 대상의 분석이 이미 진행 중이라 그 분석에 합류했습니다.
          </p>
        )}
        {run.status === 'FAILED' && (
          <p role="alert" className="text-red-700">
            {run.errorMessage ?? '실패 사유를 받지 못했습니다.'}
          </p>
        )}
        {query.isError && (
          <p className="text-xs text-amber-700">
            {`진행 상태를 새로 확인하지 못했습니다. ${query.error.message}`}
            {isTransientError(query.error) &&
            isAnalysisRunning(run.status) &&
            !pollingStopped
              ? ' 잠시 뒤 다시 확인합니다.'
              : ''}
            {pollingStopped && ' 계속 실패해 자동 확인을 멈췄습니다.'}
          </p>
        )}
        {pollingStopped && (
          <Button
            variant="secondary"
            onClick={() => {
              void query.refetch()
            }}
          >
            다시 확인
          </Button>
        )}
      </>
    )
  }

  return (
    <section
      aria-label="이번 분석"
      className="space-y-2 rounded-lg bg-slate-50 p-4 text-sm"
    >
      {body}
      <Button variant="secondary" onClick={onShowAll}>
        전체 후보 보기
      </Button>
    </section>
  )
}

function RecentAnalyses({
  currentId,
  onSelect,
}: {
  currentId?: number
  onSelect: (id: number) => void
}) {
  const query = useQuery({
    queryKey: ['analyses', 'recent'],
    queryFn: ({ signal }) => fetchRecentAnalyses(signal),
    retry: false,
  })
  if (query.isPending)
    return <LoadingState message="최근 분석을 불러오는 중…" />
  if (query.isError)
    return (
      <ErrorState
        error={query.error}
        onRetry={() => {
          void query.refetch()
        }}
      />
    )
  if (query.data.length === 0)
    return <EmptyState message="아직 실행한 분석이 없습니다." />
  return (
    <DataTable label="최근 분석">
      <thead className="bg-slate-50 text-left text-xs text-slate-500">
        <tr>
          {['분석', '종류', '상태', '요청 시각', '결과', ''].map((title) => (
            <th key={title} className="px-4 py-2 font-medium">
              {title}
            </th>
          ))}
        </tr>
      </thead>
      <tbody className="divide-y divide-slate-100 text-sm">
        {query.data.map((run) => (
          <tr key={run.id}>
            <td className="px-4 py-2 font-medium">{analysisCode(run.id)}</td>
            <td className="px-4 py-2">
              {run.runKind === 'REANALYSIS' ? '재분석' : '최초 분석'}
            </td>
            <td className="px-4 py-2">
              <StatusBadge tone={analysisStatusLabel(run.status).tone}>
                {analysisStatusLabel(run.status).label}
              </StatusBadge>
            </td>
            <td className="px-4 py-2 text-slate-600">
              {formatTime(run.requestedAt)}
            </td>
            <td className="px-4 py-2 text-slate-600">
              {run.status === 'SUCCEEDED'
                ? `후보 ${proposalCount(run)}건`
                : run.status === 'FAILED'
                  ? '실패'
                  : '-'}
            </td>
            <td className="px-4 py-2 text-right">
              <Button
                variant="secondary"
                disabled={run.id === currentId}
                aria-label={`${analysisCode(run.id)} 후보 보기`}
                onClick={() => onSelect(run.id)}
              >
                후보 보기
              </Button>
            </td>
          </tr>
        ))}
      </tbody>
    </DataTable>
  )
}

type Props = {
  currentId?: number
  joined: boolean
  onSelect: (id?: number) => void
  onStarted: (run: AnalysisRun) => void
  compact?: boolean
}

// 연결 규칙의 AI 분석 실행·진행·최근 이력
function AnalysisPanel({
  currentId,
  joined,
  onSelect,
  onStarted,
  compact = false,
}: Props) {
  const navigate = useNavigate()
  const [choosing, setChoosing] = useState(false)
  const [runKind, setRunKind] = useState<AnalysisKind>('INITIAL')
  const [showRecent, setShowRecent] = useState(false)
  // CurrentAnalysis가 채우는 019 결과를 같이 읽는다.
  const current = useQuery({
    queryKey: ['analyses', currentId],
    queryFn: ({ signal }) => fetchAnalysis(currentId!, signal),
    enabled: false,
  })
  // 이번 분석이 끝나기 전에는 새로 맡기지 않는다(중복 요청 방지).
  const running =
    currentId !== undefined &&
    current.data !== undefined &&
    isAnalysisRunning(current.data.status)

  return (
    <section
      className={
        compact
          ? 'rules-analysis-compact'
          : 'space-y-4 rounded-xl border border-slate-200 p-5'
      }
    >
      <div className="analysis-toolbar flex flex-wrap items-center justify-between gap-3">
        {!compact && <h2 className="text-lg font-semibold">AI 분석</h2>}
        <div className="flex flex-wrap items-center gap-2">
          <Button
            variant="primary"
            disabled={running}
            onClick={() => {
              setRunKind('INITIAL')
              setChoosing(true)
            }}
          >
            AI 관계 제안 받기
          </Button>
          {!compact && (
            <details className="analysis-tools" open>
              <summary>분석 관리</summary>
              <div className="analysis-tools-content flex flex-wrap gap-2">
                <Button
                  variant="secondary"
                  aria-expanded={showRecent}
                  onClick={() => setShowRecent(!showRecent)}
                >
                  {showRecent ? '최근 분석 닫기' : '최근 분석 보기'}
                </Button>
                <Button
                  variant="secondary"
                  disabled={running}
                  onClick={() => {
                    setRunKind('REANALYSIS')
                    setChoosing(true)
                  }}
                >
                  변경 데이터 재분석
                </Button>
              </div>
            </details>
          )}
        </div>
      </div>
      {running && (
        <p className="text-xs text-slate-500">
          이번 분석이 끝나면 다시 요청할 수 있습니다.
        </p>
      )}
      {currentId !== undefined && (
        <CurrentAnalysis
          key={currentId}
          id={currentId}
          joined={joined}
          onShowAll={() => onSelect(undefined)}
        />
      )}
      {showRecent && (
        <RecentAnalyses currentId={currentId} onSelect={onSelect} />
      )}
      {choosing && (
        <AnalysisTargetDialog
          runKind={runKind}
          onClose={() => setChoosing(false)}
          onStarted={(run) => {
            setChoosing(false)
            onStarted(run)
          }}
          onEditScope={(sourceId) =>
            void navigate(`/admin/sources?source=${sourceId}&tab=scope`)
          }
          onRegister={() => void navigate('/admin/sources?register=1')}
        />
      )}
    </section>
  )
}

export default AnalysisPanel
