import { formatKstDateTime } from '../../utils/dateTime'
import { useQuery } from '@tanstack/react-query'
import { ApiError, getHttpErrorMessage } from '../../api/errors'
import Button from '../../components/Button'
import DataTable from '../../components/DataTable'
import { EmptyState, ErrorState, LoadingState } from '../../components/Feedback'
import {
  validateBusinessApproval,
  type BusinessApprovalSnapshot,
  type BusinessApprovalSource,
} from './businessApproval'

const approvals: Record<string, string> = {
  NOT_REQUESTED: '미요청',
  PENDING: '승인 대기',
  APPROVED: '현업 승인',
  REJECTED: '현업 반려',
}
const applications: Record<string, string> = {
  NOT_APPLIED: '미적용',
  APPLYING: '적용 중',
  APPLIED: '적용 완료',
  FAILED: '적용 실패',
}
const decisions: Record<string, string> = { APPROVE: '승인', REJECT: '반려' }
const label = (map: Record<string, string>, status: string) =>
  Object.hasOwn(map, status) ? map[status] : `알 수 없는 상태 (${status})`
const text = (value: string | null) =>
  value === null ? '제공되지 않음' : value === '' ? '빈 값' : value
function time(value: string | null) {
  if (!value) return '제공되지 않음'
  return Number.isFinite(Date.parse(value))
    ? formatKstDateTime(value)
    : '시각을 확인할 수 없음'
}
function safeError(error: unknown) {
  return error instanceof ApiError
    ? new ApiError(getHttpErrorMessage(error.status ?? 500), {
        kind: error.kind,
        status: error.status ?? undefined,
      })
    : new Error('현업 승인 상태를 불러오지 못했습니다.')
}

function Summary({ data }: { data?: BusinessApprovalSnapshot }) {
  const cards = data
    ? [
        [
          '승인 요청',
          data.request
            ? text(data.request.reference)
            : data.approvalStatus === 'NOT_REQUESTED'
              ? '미요청'
              : '요청 정보 미제공',
        ],
        ['승인 대기·상태', label(approvals, data.approvalStatus)],
        [
          '현업 결정',
          data.decisions.length
            ? `결정 기록 ${data.decisions.length}건`
            : '제공된 결정 기록 없음',
        ],
        [
          '적용 상태',
          data.application
            ? label(applications, data.application.status)
            : '적용 정보 미제공',
        ],
      ]
    : ['승인 요청', '승인 대기·상태', '현업 결정', '적용 상태'].map((title) => [
        title,
        '미확인',
      ])
  return (
    <dl className="grid gap-3 sm:grid-cols-2 lg:grid-cols-4">
      {cards.map(([title, value]) => (
        <div
          key={title}
          className="rounded-lg border border-slate-200 bg-slate-50 p-3"
        >
          <dt className="text-sm text-slate-500">{title}</dt>
          <dd className="mt-1 break-words font-medium">{value}</dd>
        </div>
      ))}
    </dl>
  )
}

export default function BusinessApprovalStatus({
  proposalId,
  revision,
  source,
}: {
  proposalId: number
  revision: number
  source?: BusinessApprovalSource
}) {
  const valid =
    Number.isSafeInteger(proposalId) &&
    proposalId > 0 &&
    Number.isSafeInteger(revision) &&
    revision > 0
  const query = useQuery({
    queryKey: ['business-approval', proposalId, revision],
    queryFn: async ({ signal }) =>
      validateBusinessApproval(
        proposalId,
        revision,
        await source!.load(proposalId, revision, signal),
      ),
    enabled: !!source && valid,
    retry: false,
    gcTime: 0,
    refetchOnMount: 'always',
    refetchOnWindowFocus: false,
    refetchOnReconnect: false,
  })
  const canReload =
    !query.error ||
    !(query.error instanceof ApiError) ||
    ['server', 'network', 'http', 'invalid-response'].includes(query.error.kind)
  const data = query.data
  return (
    <section aria-label="현업 승인 후 적용 상태" className="space-y-4">
      <div className="flex flex-wrap items-center justify-between gap-2">
        <h3 className="font-semibold">현업 승인 후 적용 상태</h3>
        <Button
          variant="secondary"
          disabled={
            !source ||
            !valid ||
            query.isFetching ||
            query.isPaused ||
            !canReload
          }
          onClick={() => {
            void query.refetch()
          }}
        >
          현업 승인 상태 새로고침
        </Button>
      </div>
      <p className="text-sm text-slate-600">
        제안 #{proposalId} · 개정 {revision}의 현업 승인 상태입니다. 현업의
        결정과 실제 적용 상태를 각각 확인하세요.
      </p>
      {!valid ? (
        <p role="alert">올바른 제안과 개정이 필요합니다.</p>
      ) : !source ? (
        <>
          <p role="status" className="rounded-lg bg-amber-50 p-3 text-sm">
            현업 승인 상태 조회는 준비 중입니다. 절차와 조회 기능 확정 후 사용할
            수 있습니다. 현재 현업 승인 여부는 확인할 수 없습니다.
          </p>
          <Summary />
        </>
      ) : query.isPending || query.isFetching || query.isPaused ? (
        <LoadingState message="현업 승인 상태를 불러오는 중…" />
      ) : query.isError ? (
        <ErrorState
          error={safeError(query.error)}
          onRetry={
            canReload
              ? () => {
                  void query.refetch()
                }
              : undefined
          }
        />
      ) : (
        data && (
          <>
            <Summary data={data} />
            <p className="text-sm text-slate-600">
              현업 승인이 완료되어도 실제 적용이 완료된 것은 아닙니다.
            </p>
            <section aria-label="현업 승인 요청 정보" className="space-y-2">
              <h4 className="font-medium">승인 요청 정보</h4>
              {data.request ? (
                <dl className="grid gap-3 sm:grid-cols-2">
                  {[
                    ['요청 번호', text(data.request.reference)],
                    ['요청자', text(data.request.requestedBy)],
                    ['요청 시각', time(data.request.requestedAt)],
                    ['요청 사유', text(data.request.reason)],
                  ].map(([title, value]) => (
                    <div key={title}>
                      <dt className="text-sm text-slate-500">{title}</dt>
                      <dd className="whitespace-pre-wrap break-words">
                        {value}
                      </dd>
                    </div>
                  ))}
                </dl>
              ) : (
                <EmptyState
                  message={
                    data.approvalStatus === 'NOT_REQUESTED'
                      ? '현업 승인 요청이 없습니다.'
                      : '제공된 현업 승인 요청 정보가 없습니다.'
                  }
                />
              )}
            </section>
            <section aria-label="현업 결정 기록" className="space-y-2">
              <h4 className="font-medium">현업 결정 기록</h4>
              {!data.decisions.length ? (
                <EmptyState message="제공된 현업 결정 기록이 없습니다." />
              ) : (
                <DataTable label="현업 결정 기록">
                  <thead className="bg-slate-50">
                    <tr>
                      {['결정', '결정자', '결정 시각', '사유'].map((title) => (
                        <th scope="col" className="p-3" key={title}>
                          {title}
                        </th>
                      ))}
                    </tr>
                  </thead>
                  <tbody>
                    {data.decisions.map((decision, index) => (
                      <tr key={index}>
                        <td className="p-3">
                          {label(decisions, decision.decision)}
                        </td>
                        <td className="p-3">{text(decision.decidedBy)}</td>
                        <td className="p-3">{time(decision.decidedAt)}</td>
                        <td className="max-w-md whitespace-pre-wrap break-words p-3">
                          {text(decision.reason)}
                        </td>
                      </tr>
                    ))}
                  </tbody>
                </DataTable>
              )}
            </section>
            <section aria-label="현업 승인 후 적용 기록" className="space-y-2">
              <h4 className="font-medium">적용 기록</h4>
              {data.application ? (
                <>
                  <dl className="grid gap-3 sm:grid-cols-3">
                    {[
                      [
                        '적용 상태',
                        label(applications, data.application.status),
                      ],
                      ['적용자', text(data.application.appliedBy)],
                      ['적용 시각', time(data.application.appliedAt)],
                    ].map(([title, value]) => (
                      <div key={title}>
                        <dt className="text-sm text-slate-500">{title}</dt>
                        <dd className="break-words">{value}</dd>
                      </div>
                    ))}
                  </dl>
                  {data.application.error !== null && (
                    <p
                      role="alert"
                      className="whitespace-pre-wrap break-words rounded bg-red-50 p-3 text-red-800"
                    >
                      적용 실패 사유: {text(data.application.error)}
                    </p>
                  )}
                </>
              ) : (
                <EmptyState message="제공된 현업 승인 후 적용 정보가 없습니다." />
              )}
            </section>
          </>
        )
      )}
      <div className="space-y-2 border-t border-slate-200 pt-3">
        <p className="text-sm text-slate-500">
          현재는 상태 조회 화면입니다. 승인 요청·결정·적용 실행은 준비 중입니다.
        </p>
        <div className="flex flex-wrap gap-2">
          <Button disabled>승인 요청</Button>
          <Button disabled variant="secondary">
            현업 결정
          </Button>
          <Button disabled variant="secondary">
            적용 실행
          </Button>
        </div>
      </div>
    </section>
  )
}
