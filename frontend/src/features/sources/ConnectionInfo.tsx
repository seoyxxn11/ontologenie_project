import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useEffect, useState } from 'react'
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

  const detail = useQuery({
    queryKey: ['sources', sourceId, 'detail'],
    queryFn: () => fetchSource(sourceId),
  })

  const request = useMutation({
    mutationFn: () => requestConnectionCheck(sourceId),
    onSuccess: (accepted) => {
      setRequestedCheckId(accepted.checkId)
      setJoined(accepted.joined)
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
      isRunning(query.state.data?.status) ? CHECK_POLL_MS : false,
  })

  const status = check.data?.status
  const finished = requestedCheckId !== undefined && !isRunning(status)

  // 검사가 끝나면 연결 상태와 읽을 수 있는 표 목록이 바뀌므로 다시 불러온다.
  useEffect(() => {
    if (finished && status) {
      void queryClient.invalidateQueries({ queryKey: ['sources'] })
    }
  }, [finished, status, queryClient])

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
  const checking =
    request.isPending ||
    (requestedCheckId !== undefined && (!status || isRunning(status)))

  return (
    <div className="space-y-4 text-sm">
      <div className="flex flex-wrap items-start justify-between gap-3">
        <dl className="grid grid-cols-[7rem_1fr] gap-y-2">
          <dt className="text-slate-500">유형</dt>
          <dd className="text-slate-900">
            {sourceKindLabels[source.sourceKind]}
          </dd>
          <dt className="text-slate-500">접속 대상</dt>
          <dd className="font-mono text-xs text-slate-900">
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
          <dt className="text-slate-500">계정 권한</dt>
          <dd className="text-slate-900">
            {source.connectionStatus === 'CONNECTED'
              ? 'SELECT only · 검증됨'
              : '미검증'}
          </dd>
        </dl>
        <div className="flex gap-2">
          <Button variant="secondary" onClick={() => setEditing(true)}>
            정보 수정
          </Button>
          <Button
            variant="secondary"
            pending={checking}
            onClick={() => request.mutate()}
          >
            {checking ? '확인 중…' : '연결 다시 확인'}
          </Button>
        </div>
      </div>

      {savedNotice && (
        <p role="status" className="rounded-lg bg-amber-50 p-3 text-amber-800">
          {savedNotice}
        </p>
      )}
      {editing && (
        <SourceFormDialog
          source={source}
          onClose={() => setEditing(false)}
          onSaved={(_, reconnectNeeded) => {
            setEditing(false)
            setSavedNotice(
              reconnectNeeded
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
            <h4 className="font-semibold text-slate-900">연결 확인 결과</h4>
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
