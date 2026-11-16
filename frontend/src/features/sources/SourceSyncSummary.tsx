import { formatKstDateTime } from '../../utils/dateTime'
import { ApiError } from '../../api/errors'
import Button from '../../components/Button'
import { connectionStatusLabels } from './labels'
import { fetchSourceSyncSummary } from './syncApi'
import type { ConnectionStatus } from './types'
import SourceFieldHelp from './SourceFieldHelp'
import useSyncPollingQuery from './useSyncPollingQuery'

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

function SummaryStatus({
  children,
  tone = 'neutral',
}: {
  children: string
  tone?: 'neutral' | 'info' | 'success' | 'warning' | 'danger'
}) {
  return (
    <span className="sources-summary-status" data-tone={tone}>
      {children}
    </span>
  )
}

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
}: {
  sourceId: number
  sourceName: string
  connectionStatus: ConnectionStatus
}) {
  const { query, unavailable, transientError, refreshDelayed } =
    useSyncPollingQuery({
      queryKey: ['sources', sourceId, 'sync-runs', 'summary'],
      queryFn: (signal) => fetchSourceSyncSummary(sourceId, signal),
      isActive: (data) =>
        data?.latestRun?.status === 'QUEUED' ||
        data?.latestRun?.status === 'RUNNING',
    })
  const connection = connectionStatusLabels[connectionStatus]
  const latestRun = unavailable ? undefined : query.data?.latestRun
  const forbidden =
    query.error instanceof ApiError &&
    [401, 403].includes(query.error.status ?? 0)
  return (
    <section className="sources-summary" aria-label={`${sourceName} 상태 요약`}>
      <dl className="sources-summary-facts">
        <div>
          <dt>
            접속 확인{' '}
            <SourceFieldHelp label="접속 확인 기준">
              수집 계정의 접속 상태입니다. AI 분석 계정의 접속 상태나 수집 실행
              상태와는 별도입니다. 연결 검사는 접속과 읽기 가능 범위를 확인하며
              쓰기 권한을 보증하지 않습니다.
            </SourceFieldHelp>
          </dt>
          <dd>
            <SummaryStatus tone={connection.tone}>
              {connection.label}
            </SummaryStatus>
          </dd>
        </div>
        <div>
          <dt>
            최근 전체 읽기{' '}
            <SourceFieldHelp label="전체 읽기 시각 기준">
              가장 최근 접수한 전체 읽기 실행입니다. 실패한 실행의 종료 시각은
              성공 수집 시각이 아닙니다.
            </SourceFieldHelp>
          </dt>
          <dd>
            <div role="status" aria-live="polite" aria-atomic="true">
              {query.isPending ? (
                <SummaryStatus>불러오는 중…</SummaryStatus>
              ) : !query.data || unavailable ? (
                <SummaryStatus>확인 불가</SummaryStatus>
              ) : latestRun?.status === 'SUCCEEDED' ? (
                <div className="sources-summary-completed">
                  <span className="sr-only">완료 시각: </span>
                  <Time value={latestRun.finishedAt} />
                  <SummaryStatus tone="success">완료</SummaryStatus>
                </div>
              ) : latestRun ? (
                <SummaryStatus
                  tone={tones[latestRun.status]}
                >{`전체 읽기 · ${labels[latestRun.status]}`}</SummaryStatus>
              ) : (
                <SummaryStatus>전체 읽기 이력 없음</SummaryStatus>
              )}
              {latestRun && latestRun.status !== 'SUCCEEDED' && (
                <p className="sources-summary-time">
                  최근 실행 종료: <Time value={latestRun.finishedAt} />
                </p>
              )}
            </div>
          </dd>
        </div>
        <div>
          <dt>
            CDC 마지막 반영{' '}
            <SourceFieldHelp label="CDC 반영 시각 기준">
              마지막으로 반영한 원천 트랜잭션의 커밋 시각입니다. 현재 정상
              여부나 지연을 뜻하지 않으며, 원천 변경이 없으면 이전 시각이
              유지됩니다.
            </SourceFieldHelp>
          </dt>
          <dd className="sources-summary-time">
            <Time
              value={
                unavailable || query.isPending
                  ? null
                  : (query.data?.cdcLastAppliedAt ?? null)
              }
            />
          </dd>
        </div>
      </dl>
      {query.isError && (!query.data || unavailable || !transientError) && (
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
      {refreshDelayed && query.data && !unavailable && (
        <p role="status" className="sources-summary-error">
          상태 갱신이 지연되고 있습니다. 기존 내용을 유지하며 자동으로 다시
          확인합니다.
        </p>
      )}
    </section>
  )
}
