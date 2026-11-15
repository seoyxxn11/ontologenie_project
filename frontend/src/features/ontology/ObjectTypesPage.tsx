import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useState } from 'react'
import { ApiError } from '../../api/errors'
import Button from '../../components/Button'
import DataTable from '../../components/DataTable'
import { EmptyState, ErrorState, LoadingState } from '../../components/Feedback'
import PageHeader from '../../components/PageHeader'
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
  const unchanged =
    selected ===
    (type.displayPropertyId === null ? '' : String(type.displayPropertyId))
  const conflict =
    save.error instanceof ApiError && save.error.kind === 'conflict'

  return (
    <form
      className="object-types-form"
      aria-label="대표 속성 지정"
      onSubmit={(event) => {
        event.preventDefault()
        save.mutate()
      }}
    >
      <label className="flex flex-col gap-2 font-medium">
        대표 속성
        <select
          className="object-types-property-select"
          aria-describedby="object-types-property-help"
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
      <p id="object-types-property-help" className="object-types-help">
        객체의 표시 이름으로 사용합니다. 지정하지 않으면 원천 키 값을
        표시합니다.
      </p>
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
          대표 속성 저장
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
        <section className="object-types-list" aria-label="객체 유형 목록 영역">
          <header className="object-types-panel-header">
            <h2>객체 유형</h2>
            <span className="object-types-help">{types.data.length}개</span>
          </header>
          <DataTable label="객체 유형 목록" className="object-types-table">
            <colgroup>
              <col style={{ width: '46%' }} />
              <col style={{ width: '27%' }} />
              <col style={{ width: '27%' }} />
            </colgroup>
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
                  onClick={() => {
                    setSelectedId(type.id)
                    setNotice('')
                  }}
                  data-selected={type.id === selectedId || undefined}
                >
                  <td>
                    <Button
                      variant="text"
                      className="object-types-select"
                      aria-pressed={type.id === selectedId}
                    >
                      {type.name}
                    </Button>
                    <p className="mt-1 break-all font-mono text-slate-500">
                      {type.code}
                    </p>
                  </td>
                  <td>
                    <span
                      className={
                        type.accessGroup ? undefined : 'object-types-help'
                      }
                    >
                      {accessGroupText(type.accessGroup)}
                    </span>
                  </td>
                  <td>
                    {type.displayPropertyId === null
                      ? '원천 키 사용'
                      : '지정됨'}
                  </td>
                </tr>
              ))}
            </tbody>
          </DataTable>
        </section>
        <section aria-label="유형 설정" className="object-types-settings">
          <header className="object-types-panel-header">
            <div className="object-types-identity">
              <h2>{selected?.name ?? '유형 설정'}</h2>
              {selected && (
                <span className="font-mono object-types-help">
                  {selected.code}
                </span>
              )}
            </div>
          </header>
          <div className="object-types-settings-body">
            {notice && (
              <p role="status" className="object-types-notice">
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
                <div className="object-types-group">
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
          </div>
        </section>
      </div>
    )
  }

  return (
    <div className="management-page object-types-page">
      <PageHeader
        title="객체 유형"
        description="대표 속성 값이 객체의 표시 이름이 됩니다. 업무 데이터 화면과 AI 업무 도우미는 이 이름으로 객체를 보여 주고 찾습니다. 조회 그룹이 없는 유형은 어느 역할에도 보이지 않습니다."
      />
      {content}
    </div>
  )
}
