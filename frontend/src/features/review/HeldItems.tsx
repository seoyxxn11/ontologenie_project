import { useQuery } from '@tanstack/react-query'
import { useState } from 'react'
import Button from '../../components/Button'
import DataTable from '../../components/DataTable'
import Dialog from '../../components/Dialog'
import { EmptyState, ErrorState, LoadingState } from '../../components/Feedback'
import Pagination from '../../components/Pagination'
import {
  fetchHeldItem,
  fetchHeldItems,
  HELD_ITEM_PAGE_SIZE,
} from './heldItemApi'

const statusLabels: Record<string, string> = {
  HELD: '보류',
  APPROVED: '승인',
  AUTO_RESOLVED: '자동 해소',
  DISMISSED: '종결',
}

function HeldItemDetail({ id, onClose }: { id: number; onClose: () => void }) {
  const query = useQuery({
    queryKey: ['held-items', 'detail', id],
    queryFn: ({ signal }) => fetchHeldItem(id, signal),
    retry: false,
    refetchOnMount: 'always',
    refetchOnWindowFocus: false,
    refetchOnReconnect: false,
  })
  const item = query.data
  return (
    <Dialog title={`개별 보류 건 #${id}`} onClose={onClose} size="wide">
      <div className="mt-4">
        {query.isPending || query.isFetching || query.isPaused ? (
          <LoadingState message="보류 건 상세를 불러오는 중…" />
        ) : query.isError ? (
          <ErrorState
            error={query.error}
            onRetry={() => void query.refetch()}
          />
        ) : item ? (
          <>
            <dl className="grid gap-4 sm:grid-cols-2">
              {[
                ['당시 상태', statusLabels[item.status] ?? item.status],
                ['보류 사유', item.holdReason],
                ['원천 객체 ID', item.sourceObjectId],
                ['원천 값', item.rawValue],
                ['변환 값', item.transformedValue],
                ['관련 제안 ID', item.proposalItemId],
                ['관계 유형 ID', item.linkTypeId],
                ['연결 규칙 ID', item.linkMappingId],
                ['등록 시각', new Date(item.createdAt).toLocaleString('ko-KR')],
                [
                  '처리 시각',
                  item.resolvedAt &&
                    new Date(item.resolvedAt).toLocaleString('ko-KR'),
                ],
                ['처리자 ID', item.resolvedBy],
                ['처리 연결 ID', item.resolvedLinkId],
                ['처리 수집 실행 ID', item.resolvedSyncRunId],
                ['처리 기록', item.resolutionNote],
              ].map(([label, value]) => (
                <div key={label}>
                  <dt className="text-sm text-slate-500">{label}</dt>
                  <dd className="whitespace-pre-wrap break-words">
                    {value ?? '제공되지 않음'}
                  </dd>
                </div>
              ))}
            </dl>
          </>
        ) : null}
      </div>
    </Dialog>
  )
}

export default function HeldItems({
  page,
  onPageChange,
}: {
  page: number
  onPageChange: (page: number) => void
}) {
  const [detailId, setDetailId] = useState<number>()
  const query = useQuery({
    queryKey: ['held-items', 'list', page],
    queryFn: ({ signal }) => fetchHeldItems(page, signal),
    retry: false,
  })

  return (
    <section className="space-y-4" aria-label="기존 보류 이력">
      <h3 className="font-semibold">기존 보류 이력</h3>
      <p className="text-sm text-slate-600">
        이전에 승인 반영 중 보류된 기록입니다. 현재 처리 상태와 재검증 결과는
        문제 상세에서 확인할 수 있습니다.
      </p>
      {query.isPending ? (
        <LoadingState message="개별 보류 건을 불러오는 중…" />
      ) : query.isError ? (
        <ErrorState error={query.error} onRetry={() => void query.refetch()} />
      ) : (
        <>
          {query.data.data.length === 0 ? (
            <EmptyState message="이 페이지에 개별 보류 건이 없습니다." />
          ) : (
            <DataTable label="개별 보류 건 목록">
              <thead className="bg-slate-50 text-slate-600">
                <tr>
                  {['보류 건', '제안', '당시 상태', '보류 사유'].map(
                    (label) => (
                      <th key={label} className="p-3">
                        {label}
                      </th>
                    ),
                  )}
                </tr>
              </thead>
              <tbody>
                {query.data.data.map((item) => (
                  <tr
                    key={item.id}
                    className="border-t border-slate-200 align-top"
                  >
                    <td className="p-3">
                      <Button
                        variant="secondary"
                        onClick={() => setDetailId(item.id)}
                      >
                        보류 건 #{item.id}
                      </Button>
                    </td>
                    <td className="p-3">
                      {item.proposalItemId === null
                        ? '제공되지 않음'
                        : `#${item.proposalItemId}`}
                    </td>
                    <td className="p-3">
                      {statusLabels[item.status] ?? item.status}
                    </td>
                    <td className="max-w-md whitespace-pre-wrap break-words p-3">
                      {item.holdReason ?? '제공되지 않음'}
                    </td>
                  </tr>
                ))}
              </tbody>
            </DataTable>
          )}
          <Pagination
            page={page}
            size={HELD_ITEM_PAGE_SIZE}
            totalElements={query.data.meta.totalElements}
            onChange={onPageChange}
          />
        </>
      )}
      {detailId !== undefined && (
        <HeldItemDetail
          key={detailId}
          id={detailId}
          onClose={() => setDetailId(undefined)}
        />
      )}
    </section>
  )
}
