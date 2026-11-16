import { useCallback, useId, useState, type FormEvent } from 'react'
import { useBeforeUnload, useBlocker } from 'react-router'
import {
  useMutation,
  useMutationState,
  useQuery,
  useQueryClient,
} from '@tanstack/react-query'
import Button from '../../components/Button'
import Dialog from '../../components/Dialog'
import { ErrorState, LoadingState } from '../../components/Feedback'
import { ApiError } from '../../api/errors'
import {
  fetchSyncSchedule,
  saveSyncSchedule,
  type SyncSchedule,
} from './scheduleApi'

function ScheduleForm({
  schedule,
  submitted,
  save,
}: {
  schedule: SyncSchedule
  submitted?: SyncSchedule
  save: {
    submit: (schedule: SyncSchedule) => void
    pending: boolean
    error: Error | null
    succeeded: boolean
    locked: boolean
  }
}) {
  const intervalErrorId = useId()
  const [enabled, setEnabled] = useState((submitted ?? schedule).syncEnabled)
  const [interval, setInterval] = useState(
    String((submitted ?? schedule).syncIntervalMinutes ?? ''),
  )
  const minutes = Number(interval)
  const validInterval =
    /^\d+$/.test(interval) && minutes > 0 && minutes <= 2147483647
  const invalid = enabled && !validInterval
  const dirty =
    enabled !== schedule.syncEnabled ||
    (validInterval ? minutes : null) !== schedule.syncIntervalMinutes ||
    invalid
  const protect = dirty && !save.pending
  const blocker = useBlocker(
    ({ currentLocation, nextLocation }) =>
      protect &&
      (currentLocation.pathname !== nextLocation.pathname ||
        currentLocation.search !== nextLocation.search),
  )
  useBeforeUnload(
    useCallback(
      (event) => {
        if (protect || save.pending) {
          event.preventDefault()
          event.returnValue = ''
        }
      },
      [protect, save.pending],
    ),
  )

  function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    if (save.pending || save.locked || invalid) return
    save.submit({
      syncEnabled: enabled,
      syncIntervalMinutes: validInterval ? minutes : null,
      rowVersion: schedule.rowVersion,
    })
  }

  return (
    <section
      aria-label="자동 수집 주기"
      className="space-y-3 border-t border-slate-200 pt-4"
    >
      <h3 className="font-semibold">자동 수집 주기</h3>
      {blocker.state === 'blocked' && (
        <Dialog
          title="변경사항을 버릴까요?"
          onClose={() => blocker.reset()}
          closeOnBackdrop={false}
        >
          <p>저장하지 않은 수집 주기 입력은 사라집니다.</p>
          <Button variant="secondary" onClick={() => blocker.reset()}>
            계속 편집
          </Button>
          <Button variant="danger" onClick={() => blocker.proceed()}>
            변경사항 버리기
          </Button>
        </Dialog>
      )}
      {save.pending && (
        <p role="status">
          저장 요청을 처리 중입니다. 이동해도 요청은 취소되지 않습니다. 데이터
          연결 화면의 저장 요청 상태에서 결과를 확인하세요.
        </p>
      )}
      <p className="text-slate-600">
        전체 비교(FULL_COMPARE) 방식으로 수집하는 대상에만 적용됩니다. CDC 변경
        반영 간격을 바꾸는 설정이 아닙니다.
      </p>
      <form onSubmit={submit}>
        <fieldset disabled={save.pending || save.locked} className="space-y-3">
          <label className="flex items-center gap-2">
            <input
              type="checkbox"
              checked={enabled}
              onChange={(event) => setEnabled(event.target.checked)}
            />
            자동 수집 사용
          </label>
          {enabled && (
            <label className="flex flex-col gap-1">
              수집 주기(분)
              <input
                type="number"
                min={1}
                max={2147483647}
                step={1}
                required
                value={interval}
                onChange={(event) => setInterval(event.target.value)}
                aria-invalid={invalid}
                aria-describedby={invalid ? intervalErrorId : undefined}
                className="w-36 rounded-lg border border-slate-300 px-3 py-2"
              />
            </label>
          )}
          {invalid && (
            <p id={intervalErrorId} className="text-red-700">
              수집 주기를 1분 이상의 정수로 입력해 주세요.
            </p>
          )}
          <Button
            type="submit"
            disabled={save.pending || save.locked || invalid}
          >
            {save.pending ? '저장 중…' : '수집 주기 저장'}
          </Button>
          {save.error && <ErrorState error={save.error} />}
          {save.succeeded &&
            enabled === schedule.syncEnabled &&
            (validInterval ? minutes : null) ===
              schedule.syncIntervalMinutes && (
              <p role="status" className="ui-save-notice">
                수집 주기를 저장했습니다.
              </p>
            )}
        </fieldset>
      </form>
    </section>
  )
}

export default function SourceSyncSchedule({ sourceId }: { sourceId: number }) {
  const client = useQueryClient()
  const [generation, setGeneration] = useState(0)
  const queryKey = ['sources', sourceId, 'sync-schedule']
  const mutationKey = ['form-save', 'sync-schedule', sourceId]
  const requests = useMutationState({
    filters: { mutationKey, exact: true },
    select: (mutation) => ({
      status: mutation.state.status,
      error: mutation.state.error,
      variables: mutation.state.variables as SyncSchedule | undefined,
    }),
  })
  const latest = requests.at(-1)
  const pending = latest?.status === 'pending'
  const error = latest?.error ?? null
  const query = useQuery({
    queryKey,
    queryFn: ({ signal }) => fetchSyncSchedule(sourceId, signal),
    retry: false,
    refetchOnWindowFocus: false,
    refetchOnReconnect: false,
  })
  const save = useMutation({
    mutationKey,
    gcTime: Infinity,
    meta: {
      formSave: {
        label: `원천 ${sourceId} 수집 주기`,
        href: `/admin/sources?source=${sourceId}&tab=scope`,
      },
    },
    mutationFn: (schedule: SyncSchedule) =>
      saveSyncSchedule(sourceId, schedule),
    retry: false,
    onSuccess: async (schedule) => {
      client.setQueryData(queryKey, schedule)
      await Promise.all([
        client.invalidateQueries({ queryKey }),
        client.invalidateQueries({ queryKey: ['sources', 'list'] }),
        client.invalidateQueries({ queryKey: ['sources', sourceId, 'detail'] }),
        client.invalidateQueries({ queryKey: ['audit'] }),
      ])
    },
  })
  const locked =
    !!error && (!(error instanceof ApiError) || error.kind !== 'validation')
  async function reload() {
    if (client.isMutating({ mutationKey, exact: true }) || query.isFetching)
      return
    const result = await query.refetch()
    if (result.isSuccess) {
      for (const mutation of client
        .getMutationCache()
        .findAll({ mutationKey, exact: true })) {
        if (mutation.state.status !== 'pending')
          client.getMutationCache().remove(mutation)
      }
      setGeneration((value) => value + 1)
    }
  }
  if (query.isPending)
    return <LoadingState message="수집 주기 설정을 불러오는 중…" />
  if (query.isError)
    return (
      <ErrorState
        error={query.error}
        onRetry={() => {
          void reload()
        }}
      />
    )
  return (
    <>
      <ScheduleForm
        key={`${sourceId}:${query.data.rowVersion}:${generation}`}
        schedule={query.data}
        submitted={error || pending ? latest?.variables : undefined}
        save={{
          submit: (schedule) => {
            if (
              client.isMutating({ mutationKey, exact: true }) ||
              query.isFetching ||
              locked
            )
              return
            save.mutate(schedule)
          },
          pending: pending || query.isFetching,
          error,
          succeeded: latest?.status === 'success',
          locked,
        }}
      />
      {locked && (
        <p role="status">
          저장 결과나 최신 버전을 확인해야 합니다. 최신 설정을 다시 불러온 뒤
          저장 여부와 입력을 확인해 주세요.
        </p>
      )}
      <p className="mt-2 text-sm text-slate-600">
        최신 설정을 다시 불러오면 편집 중인 입력을 서버에 저장된 값으로
        바꿉니다.
      </p>
      <Button
        variant="secondary"
        disabled={pending || query.isFetching}
        onClick={() => {
          void reload()
        }}
      >
        최신 설정 다시 불러오기
      </Button>
    </>
  )
}
