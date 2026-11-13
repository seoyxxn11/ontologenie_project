import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useEffect, useRef, useState } from 'react'
import Button from '../../components/Button'
import { ErrorState, LoadingState } from '../../components/Feedback'
import StatusBadge from '../../components/StatusBadge'
import {
  fetchConnectionCheck,
  fetchSource,
  requestConnectionCheck,
} from './api'
import { checkStatusLabels, purposeLabels, sourceKindLabels } from './labels'
import SourceFormDialog from './SourceFormDialog'
import type { AccountCheck, CheckStatus } from './types'

// 배치가 검사를 끝낼 때까지 결과(100)를 다시 불러오는 간격
export const CHECK_POLL_MS = 2000
// 결과를 이만큼 기다려도 끝나지 않으면 다시 불러오기를 멈춘다
export const CHECK_POLL_LIMIT_MS = 5 * 60 * 1000

function isRunning(status: CheckStatus | undefined) {
  return status === 'QUEUED' || status === 'RUNNING'
}

function accountResult(account: AccountCheck) {
  if (account.status === 'FAILED') {
    return `실패 · ${account.errorMessage ?? '사유 없음'}`
  }
  return `읽을 수 있는 표 ${account.readableTableCount ?? '-'}개 · 컬럼 ${account.readableColumnCount ?? '-'}개`
}

// 접속 정보 탭: 011 상세와 013 연결 다시 확인, 100 계정별 결과
function ConnectionInfo({
  sourceId,
  notice,
}: {
  sourceId: number
  // 등록·수정 직후 화면에 남길 안내
  notice?: string
}) {
  const queryClient = useQueryClient()
  const [requestedCheckId, setRequestedCheckId] = useState<number>()
  const [joined, setJoined] = useState(false)
  const [editing, setEditing] = useState(false)
  const [savedNotice, setSavedNotice] = useState(notice ?? '')
  // 시간 상한까지 끝나지 않아 더 기다리지 않는 검사
  const [expiredCheckId, setExpiredCheckId] = useState<number>()
  // 다시 확인을 누를 때마다 늘린다. 같은 검사에 합류해도 시간 상한을 처음부터 다시 잰다
  const [requestCount, setRequestCount] = useState(0)

  const detail = useQuery({
    queryKey: ['sources', sourceId, 'detail'],
    queryFn: () => fetchSource(sourceId),
  })

  const request = useMutation({
    mutationFn: () => requestConnectionCheck(sourceId),
    onSuccess: (accepted) => {
      setRequestedCheckId(accepted.checkId)
      setJoined(accepted.joined)
      setExpiredCheckId(undefined)
      setRequestCount((count) => count + 1)
      setSavedNotice('')
    },
  })

  // 방금 요청한 검사가 없으면 상세에 실린 가장 최근 검사를 보여 준다.
  const checkId =
    requestedCheckId ?? detail.data?.latestConnectionCheck?.checkId
  const check = useQuery({
    queryKey: ['sources', sourceId, 'connection-checks', checkId],
    queryFn: () => fetchConnectionCheck(sourceId, checkId!),
    enabled: checkId !== undefined,
    refetchInterval: (query) =>
      isRunning(query.state.data?.status) && expiredCheckId !== checkId
        ? CHECK_POLL_MS
        : false,
  })

  const status = check.data?.status
  const running = isRunning(status)
  const expired = checkId !== undefined && expiredCheckId === checkId

  useEffect(() => {
    if (checkId === undefined || !running) return
    const timer = setTimeout(
      () => setExpiredCheckId(checkId),
      CHECK_POLL_LIMIT_MS,
    )
    return () => clearTimeout(timer)
  }, [checkId, running, requestCount])

  // 검사가 끝나면 연결 상태와 읽을 수 있는 표 목록이 바뀌므로 다시 불러온다.
  // 이 화면에서 요청한 검사가 끝났을 때와, 보여 주던 검사(다른 곳에서 요청한 것도)가 진행 중에서 끝남으로 바뀌었을 때다.
  const lastSeen = useRef<{ checkId?: number; status?: CheckStatus }>({})
  useEffect(() => {
    const before = lastSeen.current
    lastSeen.current = { checkId, status }
    if (!status || isRunning(status)) return
    const sameCheck = before.checkId === checkId
    if (sameCheck && before.status === status) return
    if (checkId === requestedCheckId || (sameCheck && isRunning(before.status)))
      void queryClient.invalidateQueries({ queryKey: ['sources'] })
  }, [checkId, status, requestedCheckId, queryClient])

  if (detail.isPending) {
    return <LoadingState message="접속 정보를 불러오는 중…" />
  }
  if (detail.isError) {
    return (
      <ErrorState
        error={detail.error}
        onRetry={() => {
          void detail.refetch()
        }}
      />
    )
  }

  const source = detail.data
  const { host, port, database } = source.connectionConfig
  // 결과 조회가 실패했거나 시간 상한을 넘기면 다시 요청할 수 있게 버튼을 푼다.
  const checking =
    request.isPending ||
    (requestedCheckId !== undefined &&
      !check.isError &&
      !expired &&
      (!status || isRunning(status)))

  return (
    <div className="space-y-4 text-sm">
      <div className="flex flex-wrap items-start justify-between gap-3">
        <dl className="sources-facts">
          <dt className="text-slate-500">유형</dt>
          <dd className="text-slate-900">
            {sourceKindLabels[source.sourceKind]}
          </dd>
          <dt className="text-slate-500">접속 대상</dt>
          <dd className="font-mono text-slate-900">
            {host
              ? `${host}${port ? `:${port}` : ''}${database ? ` · ${database}` : ''}`
              : '-'}
          </dd>
          <dt className="text-slate-500">계정</dt>
          <dd className="text-slate-900">
            {source.credentials.length
              ? source.credentials
                  .map(
                    (credential) =>
                      `${purposeLabels[credential.purpose]} ${credential.username}`,
                  )
                  .join(' · ')
              : '-'}
          </dd>
          <dt className="text-slate-500">접속 확인</dt>
          <dd className="text-slate-900">
            {/* 연결 검사는 읽기 전용 세션으로 접속해 SELECT 가능한 표를 셀 뿐, 쓰기 권한이 없는지는 검사하지 않는다 */}
            {source.connectionStatus === 'CONNECTED'
              ? '읽기 전용으로 접속됨'
              : '미검증'}
          </dd>
        </dl>
        <div className="sources-connection-actions">
          <Button variant="secondary" onClick={() => setEditing(true)}>
            정보 수정
          </Button>
          <Button
            variant="secondary"
            pending={checking}
            disabled={source.sourceKind !== 'POSTGRES'}
            onClick={() => request.mutate()}
          >
            {checking ? '확인 중…' : '연결 다시 확인'}
          </Button>
        </div>
      </div>

      {source.sourceKind !== 'POSTGRES' && (
        <p>CSV·문서 원천의 파일 업로드·연결 검사는 현재 지원하지 않습니다.</p>
      )}

      {savedNotice && (
        <p role="status" className="rounded-lg bg-amber-50 p-3 text-amber-800">
          {savedNotice}
        </p>
      )}
      {editing && (
        <SourceFormDialog
          source={source}
          onClose={() => setEditing(false)}
          onSaved={(_, reconnectNeeded, endpointChanged) => {
            setEditing(false)
            setSavedNotice(
              endpointChanged
                ? '접속 대상이 바뀌었습니다. CDC로 반영하던 표는 CDC 재연결 전까지 반영을 멈춥니다. 연결 다시 확인을 눌러 주세요.'
                : reconnectNeeded
                  ? '접속 정보가 바뀌었습니다. 연결 다시 확인을 눌러 주세요.'
                  : '저장했습니다.',
            )
          }}
        />
      )}

      {request.isError && <ErrorState error={request.error} />}

      {checkId !== undefined && (
        <section
          aria-label="연결 확인 결과"
          className="space-y-2 rounded-lg border border-slate-200 p-4"
        >
          <div className="flex items-center gap-2">
            <h3 className="font-semibold text-slate-900">연결 확인 결과</h3>
            {status && (
              <StatusBadge tone={checkStatusLabels[status].tone}>
                {checkStatusLabels[status].label}
              </StatusBadge>
            )}
          </div>
          {joined && (
            <p className="text-xs text-slate-500">
              이미 진행 중인 확인이 있어 그 결과를 함께 보여 줍니다.
            </p>
          )}
          {check.isError && (
            <ErrorState
              error={check.error}
              onRetry={() => {
                void check.refetch()
              }}
            />
          )}
          {expired && running && (
            <p className="text-slate-600">
              검사 결과를 아직 받지 못해 더 기다리지 않습니다. 잠시 뒤 연결 다시
              확인을 눌러 주세요.
            </p>
          )}
          {check.data?.errorMessage && (
            <p role="alert" className="text-red-700">
              {check.data.errorMessage}
            </p>
          )}
          {check.data && check.data.result.accounts.length > 0 && (
            <ul aria-label="계정별 결과" className="space-y-1">
              {check.data.result.accounts.map((account) => (
                <li
                  key={account.purpose}
                  className={
                    account.status === 'FAILED'
                      ? 'text-red-700'
                      : 'text-slate-700'
                  }
                >
                  {purposeLabels[account.purpose]}: {accountResult(account)}
                </li>
              ))}
            </ul>
          )}
        </section>
      )}
    </div>
  )
}

export default ConnectionInfo
