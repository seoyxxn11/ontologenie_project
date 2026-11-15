import { useMutation, useQueryClient } from '@tanstack/react-query'
import { useState } from 'react'
import { ApiError } from '../../api/errors'
import Button from '../../components/Button'
import { ErrorState } from '../../components/Feedback'
import {
  accessGroupLabels,
  policyViolations,
  selectableAccessGroups,
  violationKindLabels,
} from './accessGroups'
import { objectTypesKey, saveAccessGroup } from './api'
import type { ObjectTypeItem } from './types'

// 유형의 조회 그룹 지정(024, 392). 그룹이 없으면 어느 역할에도 보이지 않는다.
// 켜진 매핑이 원천 데이터 정책을 어기면 서버가 거절하고(402) 어긴 매핑을 알려 준다.
export default function AccessGroupEditor({
  type,
  onSaved,
}: {
  type: ObjectTypeItem
  onSaved: (saved: ObjectTypeItem) => void
}) {
  const client = useQueryClient()
  const current = type.accessGroup ?? ''
  const [selected, setSelected] = useState(current)
  const save = useMutation({
    mutationFn: () => saveAccessGroup(type, selected === '' ? null : selected),
    onSuccess: (saved) => {
      client.setQueryData<ObjectTypeItem[]>(objectTypesKey, (types) =>
        types?.map((item) => (item.id === saved.id ? saved : item)),
      )
      onSaved(saved)
    },
  })
  const conflict =
    save.error instanceof ApiError && save.error.kind === 'conflict'
  const violations =
    save.error instanceof ApiError ? policyViolations(save.error.details) : []
  // 지금 값이 고를 수 없는 그룹(FINANCE·HR)이어도 선택칸에서 보이게 둔다
  const options: string[] = [...selectableAccessGroups]
  if (current && !options.includes(current)) options.push(current)

  return (
    <form
      className="object-types-form"
      aria-label="조회 그룹 지정"
      onSubmit={(event) => {
        event.preventDefault()
        save.mutate()
      }}
    >
      <label className="flex flex-col gap-2 font-medium">
        조회 그룹
        <select
          className="object-types-property-select"
          aria-describedby="object-types-group-help"
          value={selected}
          onChange={(event) => {
            setSelected(event.target.value)
            save.reset()
          }}
        >
          <option value="">비공개 (그룹 없음)</option>
          {options.map((group) => (
            <option key={group} value={group}>
              {accessGroupLabels[group] ?? group} ({group})
            </option>
          ))}
        </select>
      </label>
      <p id="object-types-group-help" className="object-types-help">
        그룹이 없으면 어느 역할에도 이 유형의 객체가 보이지 않습니다.
      </p>
      <details className="object-types-policy">
        <summary>원천 데이터 정책</summary>
        <p className="object-types-help">
          원천 데이터 정책을 어기는 매핑이 있으면 지정할 수 없습니다.
        </p>
      </details>
      {save.isError && (
        <ErrorState
          error={save.error}
          title={
            conflict
              ? '다른 사람이 먼저 이 유형을 바꿨습니다. 목록을 다시 불러온 뒤 저장해 주세요.'
              : violations.length
                ? '원천 데이터 정책을 어기는 매핑이 있어 그룹을 지정할 수 없습니다. 매핑을 고친 뒤 지정하세요.'
                : undefined
          }
        />
      )}
      {violations.length > 0 && (
        <ul aria-label="정책을 어긴 매핑" className="space-y-1 text-sm">
          {violations.map((violation, index) => (
            <li key={index} className="font-mono">
              {violation.resourceKey}.{violation.columnName}
              {violation.propertyCode
                ? ` → ${violation.propertyCode}`
                : ''} · {violationKindLabels[violation.kind] ?? violation.kind}
            </li>
          ))}
        </ul>
      )}
      <div className="flex flex-wrap gap-2">
        <Button
          type="submit"
          pending={save.isPending}
          disabled={selected === current}
        >
          그룹 저장
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
