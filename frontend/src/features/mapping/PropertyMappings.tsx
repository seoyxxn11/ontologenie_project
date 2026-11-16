import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useState } from 'react'
import Button from '../../components/Button'
import DataTable from '../../components/DataTable'
import { EmptyState, ErrorState, LoadingState } from '../../components/Feedback'
import Pagination from '../../components/Pagination'
import { transformLabels } from '../review/ruleDraft'
import { fetchColumns } from '../sources/api'
import {
  deletePropertyMapping,
  fetchPropertyDefinitions,
  mappingDeleteMessage,
  fetchPropertyMappings,
  mappingPageSize,
  type ObjectMapping,
  type PropertyMapping,
} from './api'
import PropertyMappingDialog from './PropertyMappingDialog'
import type { MappingPreviewAccess } from './previewModel'

export default function PropertyMappings({
  sourceId,
  parent,
  objectName,
  previewAccess,
  previewReadable = false,
}: {
  sourceId: number
  parent: ObjectMapping
  objectName: string
  previewAccess?: MappingPreviewAccess
  previewReadable?: boolean
}) {
  const client = useQueryClient()
  const [page, setPage] = useState(0)
  const [editing, setEditing] = useState<'new' | PropertyMapping>()
  const [notice, setNotice] = useState('')
  const list = useQuery({
    queryKey: ['source-mappings', 'properties', parent.id, page],
    queryFn: () => fetchPropertyMappings(parent.id, page),
  })
  const columns = useQuery({
    queryKey: [
      'sources',
      sourceId,
      'resources',
      parent.sourceResourceId,
      'columns',
    ],
    queryFn: () => fetchColumns(sourceId, parent.sourceResourceId),
  })
  const definitions = useQuery({
    queryKey: ['source-mappings', 'property-definitions', parent.objectTypeId],
    queryFn: () => fetchPropertyDefinitions(parent.objectTypeId),
  })
  const remove = useMutation({
    mutationFn: (mapping: PropertyMapping) => deletePropertyMapping(mapping.id),
    onMutate: () => setNotice(''),
    onSuccess: (result) => setNotice(mappingDeleteMessage(result)),
    // 실패해도 다른 사용자가 먼저 지웠을 수 있어 목록을 다시 읽는다.
    onSettled: () =>
      client.invalidateQueries({
        queryKey: ['source-mappings', 'properties', parent.id],
      }),
  })
  const presentColumns =
    columns.data?.filter((column) => column.isPresent) ?? []
  const canRegister =
    columns.isSuccess &&
    definitions.isSuccess &&
    presentColumns.length > 0 &&
    definitions.data.length > 0
  const canEdit =
    columns.isSuccess &&
    definitions.isSuccess &&
    !list.isFetching &&
    !remove.isPending

  function showSource(mapping: PropertyMapping) {
    const column = columns.data?.find(
      (item) => item.id === mapping.sourceColumnId,
    )
    if (column)
      return column.columnName + (column.isPresent ? '' : ' (현재 컬럼에 없음)')
    return (
      mapping.sourcePath ??
      (mapping.sourceColumnId == null ? '—' : `컬럼 #${mapping.sourceColumnId}`)
    )
  }

  return (
    <section
      className="space-y-4 rounded-lg border border-slate-200 p-4"
      aria-label="객체 매핑 상세"
    >
      <div className="flex flex-wrap items-center justify-between gap-2">
        <div>
          <h4 className="font-semibold">{objectName} 속성 매핑</h4>
          <p className="mt-1 text-sm text-slate-600">
            객체 매핑 #{parent.id} · 키 컬럼 {parent.keyColumns.join(' + ')}
          </p>
        </div>
        <Button
          variant="secondary"
          disabled={!canRegister}
          onClick={() => setEditing('new')}
        >
          + 속성 매핑 등록
        </Button>
      </div>
      {notice && (
        <p
          role="status"
          className="rounded-lg bg-slate-50 p-3 text-sm text-slate-700"
        >
          {notice}
        </p>
      )}
      {remove.isError && (
        <ErrorState
          error={remove.error}
          title="속성 매핑을 삭제하지 못했습니다."
        />
      )}
      {columns.isError && (
        <ErrorState
          error={columns.error}
          title="원천 컬럼을 불러오지 못했습니다."
          onRetry={() => void columns.refetch()}
        />
      )}
      {definitions.isError && (
        <ErrorState
          error={definitions.error}
          title="객체 속성을 불러오지 못했습니다."
          onRetry={() => void definitions.refetch()}
        />
      )}
      {(columns.isPending || definitions.isPending) && (
        <LoadingState message="원천 컬럼과 객체 속성을 불러오는 중…" />
      )}
      {columns.isSuccess && definitions.isSuccess && !canRegister && (
        <p className="text-sm text-slate-600">
          속성 매핑을 등록하려면 현재 원천 컬럼과 등록된 객체 속성이 필요합니다.
        </p>
      )}
      {list.isPending && <LoadingState message="속성 매핑을 불러오는 중…" />}
      {list.isError && (
        <ErrorState error={list.error} onRetry={() => void list.refetch()} />
      )}
      {list.isSuccess && (
        <>
          {list.data.data.length ? (
            <DataTable label="속성 매핑 목록">
              <thead className="bg-slate-50 text-left text-xs text-slate-600">
                <tr>
                  {['원천 컬럼', '변환 단계', '대상 속성', '관리'].map(
                    (heading) => (
                      <th
                        key={heading}
                        scope="col"
                        className="px-4 py-3 font-medium"
                      >
                        {heading}
                      </th>
                    ),
                  )}
                </tr>
              </thead>
              <tbody className="divide-y divide-slate-100 text-sm">
                {list.data.data.map((mapping) => {
                  const name =
                    definitions.data?.find(
                      (item) => item.id === mapping.propertyDefinitionId,
                    )?.name ?? `속성 #${mapping.propertyDefinitionId}`
                  return (
                    <tr key={mapping.id}>
                      <td className="px-4 py-3">{showSource(mapping)}</td>
                      <td className="px-4 py-3">
                        {mapping.transformSteps.length
                          ? mapping.transformSteps
                              .map(({ op, arg }) => {
                                const label = Object.hasOwn(transformLabels, op)
                                  ? transformLabels[op]
                                  : op
                                return arg == null
                                  ? label
                                  : `${label} (${typeof arg === 'string' ? arg : JSON.stringify(arg)})`
                              })
                              .join(' → ')
                          : '그대로 연결'}
                      </td>
                      <th
                        scope="row"
                        className="px-4 py-3 text-left font-medium"
                      >
                        {name}
                      </th>
                      <td className="px-4 py-3">
                        <div className="flex gap-2">
                          <Button
                            variant="secondary"
                            disabled={!canEdit}
                            aria-label={`${name} 속성 매핑 편집`}
                            onClick={() => setEditing(mapping)}
                          >
                            편집
                          </Button>
                          <Button
                            variant="secondary"
                            disabled={!canEdit}
                            pending={
                              remove.isPending &&
                              remove.variables.id === mapping.id
                            }
                            aria-label={`${name} 속성 매핑 삭제`}
                            onClick={() => {
                              if (
                                window.confirm(
                                  `${name} 속성 매핑을 삭제할까요? 객체 매핑이 켜져 있으면 다음 수집에서 원천을 처음부터 다시 읽어 이 속성 값을 뺍니다.`,
                                )
                              )
                                remove.mutate(mapping)
                            }}
                          >
                            삭제
                          </Button>
                        </div>
                      </td>
                    </tr>
                  )
                })}
              </tbody>
            </DataTable>
          ) : (
            <EmptyState message="등록된 속성 매핑이 없습니다." />
          )}
          {list.data.meta && (page > 0 || list.data.meta.hasNext) && (
            <Pagination
              page={page}
              size={mappingPageSize}
              totalElements={list.data.meta.totalElements}
              onChange={setPage}
            />
          )}
        </>
      )}
      {editing && definitions.data && (
        <PropertyMappingDialog
          sourceId={sourceId}
          parent={parent}
          existing={editing === 'new' ? undefined : editing}
          columns={presentColumns}
          properties={definitions.data}
          previewAccess={previewAccess}
          previewReadable={previewReadable}
          onClose={() => setEditing(undefined)}
          onReload={() => {
            setEditing(undefined)
            void list.refetch()
          }}
          onSaved={(message) => {
            setEditing(undefined)
            setNotice(message)
            remove.reset()
            void client.invalidateQueries({
              queryKey: ['source-mappings', 'properties', parent.id],
            })
          }}
        />
      )}
    </section>
  )
}
