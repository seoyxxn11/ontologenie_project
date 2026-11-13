import { formatKstDateTime } from '../../utils/dateTime'
import { useQuery } from '@tanstack/react-query'
import { ApiError } from '../../api/errors'
import Button from '../../components/Button'
import { fetchSourceSyncSummary } from './syncApi'

const labels = {
  QUEUED: '대기',
  RUNNING: '실행 중',
  SUCCEEDED: '완료',
  FAILED: '실패',
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
}: {
  sourceId: number
  sourceName: string
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
  if (query.isPending)
    return (
      <>
        <td className="px-4 py-3 text-slate-500">불러오는 중…</td>
        <td className="px-4 py-3 text-slate-500">불러오는 중…</td>
      </>
    )
  if (query.isError) {
    const forbidden =
      query.error instanceof ApiError &&
      [401, 403].includes(query.error.status ?? 0)
    return (
      <>
        <td className="px-4 py-3 text-red-700">
          <p>{forbidden ? '조회 권한 없음' : '요약 조회 실패'}</p>
          {!forbidden && (
            <Button
              variant="text"
              aria-label={`${sourceName} 수집 요약 다시 조회`}
              disabled={query.isFetching}
              onClick={() => void query.refetch()}
              className="mt-1 underline"
            >
              {query.isFetching ? '조회 중…' : '다시 조회'}
            </Button>
          )}
        </td>
        <td className="px-4 py-3 text-slate-500">확인 불가</td>
      </>
    )
  }
  const { latestRun, cdcLastAppliedAt } = query.data
  return (
    <>
      <td
        className={`px-4 py-3 ${latestRun?.status === 'FAILED' ? 'text-red-700' : 'text-slate-700'}`}
      >
        {latestRun
          ? `전체 읽기 · ${labels[latestRun.status]}`
          : '전체 읽기 이력 없음'}
        <Button
          variant="text"
          aria-label={`${sourceName} 수집 요약 새로고침`}
          disabled={query.isFetching}
          onClick={() => void query.refetch()}
          className="mt-1 underline"
        >
          새로고침
        </Button>
      </td>
      <td className="space-y-1 px-4 py-3 text-xs text-slate-600">
        <p>
          {latestRun?.status === 'SUCCEEDED'
            ? '전체 읽기 완료'
            : '최근 실행 종료'}
          : <Time value={latestRun?.finishedAt ?? null} />
        </p>
        <p>
          CDC 반영: <Time value={cdcLastAppliedAt} />
        </p>
      </td>
    </>
  )
}
