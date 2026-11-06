import { useQuery, useQueryClient } from '@tanstack/react-query'
import { useEffect, useState } from 'react'
import { useNavigate } from 'react-router'
import Button from '../../components/Button'
import DataTable from '../../components/DataTable'
import { EmptyState, ErrorState, LoadingState } from '../../components/Feedback'
import StatusBadge from '../../components/StatusBadge'
import { fetchAnalysis, fetchRecentAnalyses } from './api'
import AnalysisTargetDialog from './AnalysisTargetDialog'
import { analysisCode, analysisStatusLabels, isAnalysisRunning } from './labels'
import TestAnalysisDelete from './TestAnalysisDelete'
import type { Analysis, AnalysisRun } from './types'

// 분석이 끝날 때까지 019를 다시 부르는 간격
export const ANALYSIS_POLL_MS = 2000

function formatTime(value: string | null) {
  return value ? new Date(value).toLocaleString('ko-KR') : '-'
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
    refetchInterval: (current) =>
      current.state.data && !isAnalysisRunning(current.state.data.status)
        ? false
        : ANALYSIS_POLL_MS,
    retry: false,
  })
  const status = query.data?.status

  // 분석이 끝나면 새 후보와 최근 분석 목록을 다시 불러온다.
  useEffect(() => {
    if (status && !isAnalysisRunning(status)) {
      void queryClient.invalidateQueries({ queryKey: ['proposals'] })
      void queryClient.invalidateQueries({ queryKey: ['analyses', 'recent'] })
    }
  }, [status, queryClient])

  let body
  if (query.isPending)
    body = <LoadingState message="분석 상태를 불러오는 중…" />
  else if (query.isError)
    body = (
      <ErrorState
        error={query.error}
        onRetry={() => {
          void query.refetch()
        }}
      />
    )
  else {
    const run = query.data
    body = (
      <>
        <div className="flex flex-wrap items-center gap-2">
          <StatusBadge tone={analysisStatusLabels[run.status].tone}>
            {analysisStatusLabels[run.status].label}
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
        </p>
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
          {['분석', '상태', '요청 시각', '결과', ''].map((title) => (
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
              <StatusBadge tone={analysisStatusLabels[run.status].tone}>
                {analysisStatusLabels[run.status].label}
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
}

// 연결 규칙의 AI 분석 실행·진행·최근 이력(S15P31C106-108)
function AnalysisPanel({ currentId, joined, onSelect, onStarted }: Props) {
  const navigate = useNavigate()
  const [choosing, setChoosing] = useState(false)
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
    <section className="space-y-4 rounded-xl border border-slate-200 p-5">
      <div className="flex flex-wrap items-center justify-between gap-3">
        <h2 className="text-lg font-semibold">AI 분석</h2>
        <div className="flex flex-wrap items-center gap-2">
          <Button
            variant="secondary"
            aria-expanded={showRecent}
            onClick={() => setShowRecent(!showRecent)}
          >
            {showRecent ? '최근 분석 닫기' : '최근 분석 보기'}
          </Button>
          <Button disabled={running} onClick={() => setChoosing(true)}>
            AI 관계 제안 받기
          </Button>
          {/* 임시(S15P31C106-307): 시연 전 이 줄과 import를 지운다 */}
          <TestAnalysisDelete onDeleted={() => onSelect(undefined)} />
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
          onClose={() => setChoosing(false)}
          onStarted={(run) => {
            setChoosing(false)
            onStarted(run)
          }}
          onEditScope={(sourceId) =>
            void navigate(`/admin/sources?source=${sourceId}&tab=scope`)
          }
          onRegister={() => void navigate('/admin/sources')}
        />
      )}
    </section>
  )
}

export default AnalysisPanel
