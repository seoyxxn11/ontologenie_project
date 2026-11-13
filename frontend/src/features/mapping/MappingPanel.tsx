import { useQuery, useQueryClient } from '@tanstack/react-query'
import { useState } from 'react'
import Button from '../../components/Button'
import DataTable from '../../components/DataTable'
import { EmptyState, ErrorState, LoadingState } from '../../components/Feedback'
import Pagination from '../../components/Pagination'
import type { SourceResource } from '../sources/types'
import {
  fetchMappingResources,
  fetchObjectMappings,
  fetchObjectTypes,
  mappingPageSize,
  type Definition,
  type ObjectMapping,
} from './api'
import ObjectMappingDialog from './ObjectMappingDialog'
import PropertyMappings from './PropertyMappings'
import type { MappingPreviewAccess } from './previewModel'

const originLabels = {
  MANUAL: '직접 등록',
  AI_PROPOSAL: 'AI 제안',
  SEED: '초기 설정',
} satisfies Record<ObjectMapping['origin'], string>

export default function MappingPanel({
  sourceId,
  previewAccess,
}: {
  sourceId: number
  previewAccess?: MappingPreviewAccess
}) {
  const [resourceId, setResourceId] = useState<number>()
  const [notice, setNotice] = useState('')
  const resources = useQuery({
    queryKey: ['source-mappings', 'resources', sourceId],
    queryFn: () => fetchMappingResources(sourceId),
  })
  const types = useQuery({
    queryKey: ['source-mappings', 'object-types'],
    queryFn: fetchObjectTypes,
  })

  if (resources.isPending || types.isPending)
    return <LoadingState message="원천 자원과 객체 유형을 불러오는 중…" />
  if (resources.isError)
    return (
      <ErrorState
        error={resources.error}
        onRetry={() => void resources.refetch()}
      />
    )
  if (types.isError)
    return (
      <ErrorState error={types.error} onRetry={() => void types.refetch()} />
    )

  const available = resources.data
  const current =
    available.find((resource) => resource.id === resourceId) ??
    available.find((resource) => resource.isReadable) ??
    available[0]
  if (!current)
    return <EmptyState message="매핑을 조회할 원천 자원이 없습니다." />

  return (
    <section className="space-y-4" aria-label="원천 매핑">
      <p className="text-sm text-slate-600">
        원천 자원에서 객체를 식별할 키 컬럼을 고르고, 원천 컬럼을 객체 속성에
        연결하세요.
      </p>
      <label className="block max-w-md text-sm">
        원천 자원
        <select
          className="mt-1 w-full rounded border border-slate-300 bg-white p-2"
          value={current.id}
          onChange={(event) => {
            setResourceId(Number(event.target.value))
            setNotice('')
          }}
        >
          {available.map((resource) => (
            <option key={resource.id} value={resource.id}>
              {resource.resourceKey}
              {resource.isReadable ? '' : ' (읽기 범위 미확인)'}
            </option>
          ))}
        </select>
      </label>
      {notice && (
        <p
          role="status"
          className="rounded-lg bg-slate-50 p-3 text-sm text-slate-700"
        >
          {notice}
        </p>
      )}
      <ObjectMappings
        key={current.id}
        sourceId={sourceId}
        resource={current}
        resources={available}
        objectTypes={types.data}
        previewAccess={previewAccess}
        onSaved={(mapping, message) => {
          setResourceId(mapping.sourceResourceId)
          setNotice(message)
        }}
      />
    </section>
  )
}

function ObjectMappings({
  sourceId,
  resource,
  resources,
  objectTypes,
  onSaved,
  previewAccess,
}: {
  sourceId: number
  resource: SourceResource
  resources: SourceResource[]
  objectTypes: Definition[]
  onSaved: (mapping: ObjectMapping, message: string) => void
  previewAccess?: MappingPreviewAccess
}) {
  const client = useQueryClient()
  const [page, setPage] = useState(0)
  const [selectedId, setSelectedId] = useState<number>()
  const [editing, setEditing] = useState<'new' | ObjectMapping>()
  const list = useQuery({
    queryKey: ['source-mappings', 'objects', resource.id, page],
    queryFn: () => fetchObjectMappings(resource.id, page),
  })
  const selected = list.data?.data.find((mapping) => mapping.id === selectedId)
  const objectName = (id: number) =>
    objectTypes.find((type) => type.id === id)?.name ?? `객체 유형 #${id}`

  return (
    <div className="space-y-4">
      <div className="flex flex-wrap items-center justify-between gap-2">
        <h3 className="font-semibold">객체 매핑</h3>
        <Button
          variant="secondary"
          disabled={!objectTypes.length}
          onClick={() => setEditing('new')}
        >
          + 객체 매핑 등록
        </Button>
      </div>
      {!objectTypes.length && (
        <p className="text-sm text-slate-600">
          매핑을 등록하려면 등록된 객체 유형이 필요합니다.
        </p>
      )}
      {list.isPending && <LoadingState message="객체 매핑을 불러오는 중…" />}
      {list.isError && (
        <ErrorState error={list.error} onRetry={() => void list.refetch()} />
      )}
      {list.isSuccess && (
        <>
          {list.data.data.length ? (
            <DataTable label="객체 매핑 목록">
              <thead className="bg-slate-50 text-left text-xs text-slate-600">
                <tr>
                  {[
                    '객체 유형',
                    '키 컬럼',
                    '등록 방식',
                    '활성 상태',
                    '관리',
                  ].map((heading) => (
                    <th
                      key={heading}
                      scope="col"
                      className="px-4 py-3 font-medium"
                    >
                      {heading}
                    </th>
                  ))}
                </tr>
              </thead>
              <tbody className="divide-y divide-slate-100 text-sm">
                {list.data.data.map((mapping) => {
                  const name = objectName(mapping.objectTypeId)
                  return (
                    <tr
                      key={mapping.id}
                      className={
                        selected?.id === mapping.id ? 'bg-slate-50' : ''
                      }
                    >
                      <th scope="row" className="px-4 py-3 text-left">
                        <Button
                          variant="text"
                          aria-label={`${name} #${mapping.id}`}
                          aria-pressed={selected?.id === mapping.id}
                          onClick={() =>
                            setSelectedId(
                              selected?.id === mapping.id
                                ? undefined
                                : mapping.id,
                            )
                          }
                          className="font-semibold underline underline-offset-2"
                        >
                          {name}
                          <span className="ml-1 text-xs font-normal text-slate-500">
                            #{mapping.id}
                          </span>
                        </Button>
                      </th>
                      <td className="px-4 py-3">
                        {mapping.keyColumns.join(' → ')}
                      </td>
                      <td className="px-4 py-3">
                        {originLabels[mapping.origin] ?? mapping.origin}
                      </td>
                      <td className="px-4 py-3">
                        {mapping.isActive ? '활성' : '비활성'}
                      </td>
                      <td className="px-4 py-3">
                        <Button
                          variant="secondary"
                          disabled={list.isFetching}
                          aria-label={`${name} #${mapping.id} 객체 매핑 편집`}
                          onClick={() => setEditing(mapping)}
                        >
                          편집
                        </Button>
                      </td>
                    </tr>
                  )
                })}
              </tbody>
            </DataTable>
          ) : (
            <EmptyState message="이 원천 자원에 등록된 객체 매핑이 없습니다." />
          )}
          {list.data.meta && (page > 0 || list.data.meta.hasNext) && (
            <Pagination
              page={page}
              size={mappingPageSize}
              totalElements={list.data.meta.totalElements}
              onChange={(next) => {
                setPage(next)
                setSelectedId(undefined)
              }}
            />
          )}
          {selected && (
            <PropertyMappings
              key={`${selected.id}:${selected.objectTypeId}`}
              sourceId={sourceId}
              parent={selected}
              objectName={objectName(selected.objectTypeId)}
              previewAccess={previewAccess}
              previewReadable={resource.isReadable}
            />
          )}
        </>
      )}
      {editing && (
        <ObjectMappingDialog
          sourceId={sourceId}
          initialResourceId={resource.id}
          resources={resources}
          objectTypes={objectTypes}
          existing={editing === 'new' ? undefined : editing}
          previewAccess={previewAccess}
          onClose={() => setEditing(undefined)}
          onReload={() => {
            setEditing(undefined)
            void list.refetch()
          }}
          onSaved={(mapping, message) => {
            setEditing(undefined)
            void client.invalidateQueries({
              queryKey: ['source-mappings', 'objects'],
            })
            onSaved(mapping, message)
          }}
        />
      )}
    </div>
  )
}
