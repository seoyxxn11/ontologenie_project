import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useState } from 'react'
import { ApiError } from '../../api/errors'
import Button from '../../components/Button'
import DataTable from '../../components/DataTable'
import { EmptyState, ErrorState, LoadingState } from '../../components/Feedback'
import PageHeader from '../../components/PageHeader'
import StatusBadge from '../../components/StatusBadge'
import AccessGroupEditor from './AccessGroupEditor'
import { accessGroupText } from './accessGroups'
import {
  fetchObjectTypeList,
  fetchTypeProperties,
  objectTypesKey,
  saveDisplayProperty,
} from './api'
import type { ObjectTypeItem } from './types'
import './objectTypes.css'

const groupSavedNotice =
  '조회 그룹을 저장했습니다. 그룹에 허용된 역할의 업무 데이터 화면과 AI 업무 도우미에 바로 반영됩니다.'
const savedNotice =
  '저장했습니다. 이 유형이 매핑된 원천 표를 다시 읽도록 예약했습니다. 다시 읽기가 끝나면 기존 객체의 표시 이름도 바뀝니다.'

function DisplayPropertyEditor({
  type,
  onSaved,
}: {
  type: ObjectTypeItem
  onSaved: (saved: ObjectTypeItem) => void
}) {
  const client = useQueryClient()
  const [selected, setSelected] = useState(
    type.displayPropertyId === null ? '' : String(type.displayPropertyId),
  )
  const properties = useQuery({
    queryKey: [...objectTypesKey, type.id, 'properties'],
    queryFn: () => fetchTypeProperties(type.id),
  })
  const save = useMutation({
    mutationFn: () =>
      saveDisplayProperty(type, selected === '' ? null : Number(selected)),
    onSuccess: (saved) => {
      client.setQueryData<ObjectTypeItem[]>(objectTypesKey, (types) =>
        types?.map((item) => (item.id === saved.id ? saved : item)),
      )
      onSaved(saved)
    },
  })

  if (properties.isPending)
    return <LoadingState message="속성 목록을 불러오는 중…" />
  if (properties.isError)
    return (
      <ErrorState
        error={properties.error}
        onRetry={() => void properties.refetch()}
      />
    )

  const choosable = properties.data.filter(
    (property) => property.sensitivity !== 'RESTRICTED',
  )
  const restrictedCount = properties.data.length - choosable.length
  const current = properties.data.find(
    (property) => property.id === type.displayPropertyId,
  )
  const unchanged =
    selected ===
    (type.displayPropertyId === null ? '' : String(type.displayPropertyId))
  const conflict =
    save.error instanceof ApiError && save.error.kind === 'conflict'

  return (
    <form
      className="space-y-4"
      onSubmit={(event) => {
        event.preventDefault()
        save.mutate()
      }}
    >
      <div>
        <h2>{type.name}</h2>
        <p className="break-all font-mono text-slate-500">{type.code}</p>
      </div>
      <p>
        현재 대표 속성:{' '}
        {type.displayPropertyId === null
          ? '없음 (원천 키 값을 표시 이름으로 씁니다)'
          : current
            ? `${current.name} (${current.code})`
            : `속성 #${type.displayPropertyId}`}
      </p>
      <label className="flex flex-col gap-2 font-medium">
        대표 속성
        <select
          className="object-types-property-select"
          value={selected}
          onChange={(event) => {
            setSelected(event.target.value)
            save.reset()
          }}
        >
          <option value="">지정하지 않음 (원천 키 값 사용)</option>
          {choosable.map((property) => (
            <option key={property.id} value={property.id}>
              {property.name} ({property.code})
            </option>
          ))}
        </select>
      </label>
      {restrictedCount > 0 && (
        <p className="text-slate-500">
          민감 속성 {restrictedCount}개는 대표 속성으로 고를 수 없습니다.
        </p>
      )}
      {save.isError && (
        <ErrorState
          error={save.error}
          title={
            conflict
              ? '다른 사람이 먼저 이 유형을 바꿨습니다. 목록을 다시 불러온 뒤 저장해 주세요.'
              : undefined
          }
        />
      )}
      <div className="flex flex-wrap gap-2">
        <Button type="submit" pending={save.isPending} disabled={unchanged}>
          저장
        </Button>
        {conflict && (
          <Button
            variant="secondary"
            onClick={() =>
              void client.invalidateQueries({ queryKey: objectTypesKey })
            }
          >
            목록 다시 불러오기
          </Button>
        )}
      </div>
    </form>
  )
}

export default function ObjectTypesPage() {
  const [selectedId, setSelectedId] = useState<number>()
  const [notice, setNotice] = useState('')
  const types = useQuery({
    queryKey: objectTypesKey,
    queryFn: fetchObjectTypeList,
  })

  let content
  if (types.isPending)
    content = <LoadingState message="객체 유형을 불러오는 중…" />
  else if (types.isError)
    content = (
      <ErrorState error={types.error} onRetry={() => void types.refetch()} />
    )
  else if (!types.data.length)
    content = <EmptyState message="등록된 객체 유형이 없습니다." />
  else {
    const selected = types.data.find((type) => type.id === selectedId)
    content = (
      <div className="object-types-workbench">
        <DataTable label="객체 유형 목록" className="object-types-table">
          <thead>
            <tr>
              <th className="px-4 py-3">이름·코드</th>
              <th className="px-4 py-3">조회 그룹</th>
              <th className="px-4 py-3">대표 속성</th>
            </tr>
          </thead>
          <tbody>
            {types.data.map((type) => (
              <tr
                key={type.id}
                data-selected={type.id === selectedId || undefined}
              >
                <td>
                  <Button
                    variant="text"
                    className="object-types-select"
                    aria-pressed={type.id === selectedId}
                    onClick={() => {
                      setSelectedId(type.id)
                      setNotice('')
                    }}
                  >
                    {type.name}
                  </Button>
                  <p className="mt-1 break-all font-mono text-slate-500">
                    {type.code}
                  </p>
                </td>
                <td>
                  <StatusBadge tone={type.accessGroup ? 'success' : 'warning'}>
                    {accessGroupText(type.accessGroup)}
                  </StatusBadge>
                </td>
                <td>
                  <StatusBadge
                    tone={
                      type.displayPropertyId === null ? 'warning' : 'success'
                    }
                  >
                    {type.displayPropertyId === null ? '없음' : '지정됨'}
                  </StatusBadge>
                </td>
              </tr>
            ))}
          </tbody>
        </DataTable>
        <section aria-label="유형 설정" className="management-panel space-y-4">
          {notice && (
            <p role="status" className="management-subpanel">
              {notice}
            </p>
          )}
          {selected ? (
            <>
              <DisplayPropertyEditor
                key={`${selected.id}-${selected.rowVersion}`}
                type={selected}
                onSaved={() => setNotice(savedNotice)}
              />
              <div className="management-subpanel">
                <AccessGroupEditor
                  key={`group-${selected.id}-${selected.rowVersion}`}
                  type={selected}
                  onSaved={() => setNotice(groupSavedNotice)}
                />
              </div>
            </>
          ) : (
            <EmptyState message="목록에서 객체 유형을 고르세요." />
          )}
        </section>
      </div>
    )
  }

  return (
    <div className="management-page space-y-6">
      <PageHeader
        title="객체 유형"
        description="대표 속성 값이 객체의 표시 이름이 됩니다. 업무 데이터 화면과 AI 업무 도우미는 이 이름으로 객체를 보여 주고 찾습니다. 조회 그룹이 없는 유형은 어느 역할에도 보이지 않습니다."
      />
      {content}
    </div>
  )
}
