import { formatKstDateTime } from '../../utils/dateTime'
import { useQuery } from '@tanstack/react-query'
import { ApiError } from '../../api/errors'
import Button from '../../components/Button'
import StatusBadge from '../../components/StatusBadge'
import { connectionStatusLabels } from './labels'
import { fetchSourceSyncSummary } from './syncApi'
import type { ConnectionStatus } from './types'

const labels = {
  QUEUED: '대기',
  RUNNING: '실행 중',
  SUCCEEDED: '완료',
  FAILED: '실패',
}
const tones = {
  QUEUED: 'info',
  RUNNING: 'info',
  SUCCEEDED: 'success',
  FAILED: 'danger',
} as const

function Time({ value }: { value: string | null }) {
  return value ? (
    <time dateTime={value}>{formatKstDateTime(value)}</time>
  ) : (
    <>—</>
  )
}

export default function SourceSyncSummary({
  sourceId,
  sourceName,
  connectionStatus,
  onViewHistory,
}: {
  sourceId: number
  sourceName: string
  connectionStatus: ConnectionStatus
  onViewHistory?: () => void
}) {
  const query = useQuery({
    queryKey: ['sources', sourceId, 'sync-runs', 'summary'],
    queryFn: ({ signal }) => fetchSourceSyncSummary(sourceId, signal),
    retry: false,
    refetchInterval: (query) => {
      if (query.state.error) return false
      const status = query.state.data?.latestRun?.status
      return status === 'QUEUED' || status === 'RUNNING' ? 2000 : 30000
    },
  })
  const connection = connectionStatusLabels[connectionStatus]
  const latestRun = query.isError ? undefined : query.data?.latestRun
  const forbidden =
    query.error instanceof ApiError &&
    [401, 403].includes(query.error.status ?? 0)
  return (
    <section className="sources-summary" aria-label={`${sourceName} 상태 요약`}>
      <dl className="sources-summary-facts">
        <div>
          <dt>접속 확인</dt>
          <dd>
            <StatusBadge tone={connection.tone}>{connection.label}</StatusBadge>
          </dd>
        </div>
        <div>
          <dt>최근 전체 읽기</dt>
          <dd>
            <div role="status" aria-live="polite" aria-atomic="true">
              {query.isPending ? (
                '불러오는 중…'
              ) : query.isError ? (
                '확인 불가'
              ) : latestRun ? (
                <StatusBadge
                  tone={tones[latestRun.status]}
                >{`전체 읽기 · ${labels[latestRun.status]}`}</StatusBadge>
              ) : (
                '전체 읽기 이력 없음'
              )}
              {latestRun && (
                <p className="sources-summary-time">
                  {latestRun.status === 'SUCCEEDED' ? (
                    <span className="sr-only">완료 시각: </span>
                  ) : (
                    '최근 실행 종료: '
                  )}
                  <Time value={latestRun.finishedAt} />
                </p>
              )}
            </div>
          </dd>
        </div>
        <div>
          <dt>CDC 마지막 반영</dt>
          <dd className="sources-summary-time">
            <Time
              value={
                query.isError || query.isPending
                  ? null
                  : query.data.cdcLastAppliedAt
              }
            />
          </dd>
        </div>
      </dl>
      {query.isError && (
        <div role="alert" className="sources-summary-error">
          <p>{forbidden ? '조회 권한 없음' : '요약 조회 실패'}</p>
          <p>수집 상태와 시각을 확인할 수 없습니다.</p>
          {!forbidden && (
            <Button
              variant="text"
              aria-label={`${sourceName} 수집 요약 다시 조회`}
              disabled={query.isFetching}
              onClick={() => void query.refetch()}
            >
              {query.isFetching ? '조회 중…' : '다시 조회'}
            </Button>
          )}
        </div>
      )}
      {latestRun?.status === 'FAILED' && (
        <p role="alert" className="sources-summary-error">
          최근 전체 읽기가 실패했습니다. 수집 이력에서 사유를 확인해 주세요.
        </p>
      )}
      <div className="sources-summary-actions">
        <details className="sources-summary-basis">
          <summary>상태·시각 기준</summary>
          <p>
            접속 표시는 수집 계정 기준이며 AI 분석 계정의 접속 상태를 뜻하지
            않습니다. 접속 확인과 수집 실행 상태는 별도입니다. 연결 검사는 읽기
            전용 세션의 접속과 읽을 수 있는 범위를 확인하며 쓰기 권한 유무를
            보증하지 않습니다. 전체 읽기는 가장 최근에 접수한 실행 기준이며,
            실패한 실행의 종료 시각은 성공 수집 시각이 아닙니다. CDC 반영은
            마지막 반영한 원천 트랜잭션의 커밋 시각으로, 현재 CDC 정상 여부나
            반영 지연을 뜻하지 않습니다. 원천에 변경이 없으면 이전 시각이 유지될
            수 있습니다.
          </p>
        </details>
        <div className="sources-summary-buttons">
          {!query.isError && (
            <Button
              variant="text"
              aria-label={`${sourceName} 수집 요약 새로고침`}
              disabled={query.isFetching}
              onClick={() => void query.refetch()}
            >
              새로고침
            </Button>
          )}
          {onViewHistory && (
            <Button variant="text" onClick={onViewHistory}>
              수집 이력 보기
            </Button>
          )}
        </div>
      </div>
    </section>
  )
}
