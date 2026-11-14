import { useMutation, useQueryClient } from '@tanstack/react-query'
import { useState } from 'react'
import { ApiError } from '../../api/errors'
import Button from '../../components/Button'
import { ErrorState } from '../../components/Feedback'
import {
  accessGroupLabels,
  selectableAccessGroups,
} from '../ontology/accessGroups'
import { reviseObjectProposal } from './api'
import { isRow, type Row } from './bundle'
import type { Proposal } from './types'

function rows(value: unknown): Row[] {
  return Array.isArray(value) ? value.filter(isRow) : []
}

type Sensitivity = 'NORMAL' | 'RESTRICTED'

// 객체 유형 후보의 조회 그룹과 속성 민감도를 검토자가 고친다(392). 저장하면 개정본이 생기고 승인은 그 개정본으로 한다.
// 식별 속성은 민감으로 둘 수 없다(조회·연결 키라 승인 반영이 거절한다). 원천 정책의 민감 컬럼은 일반으로 두어도 반영 때 민감으로 올라간다.
export default function BundleAccessEditor({
  proposal,
}: {
  proposal: Proposal
}) {
  const client = useQueryClient()
  const properties = rows(proposal.payload.properties)
  const initialGroup =
    typeof proposal.payload.accessGroup === 'string'
      ? proposal.payload.accessGroup
      : ''
  const initialSensitivity = properties.map<Sensitivity>((property) =>
    property.sensitivity === 'RESTRICTED' ? 'RESTRICTED' : 'NORMAL',
  )
  const [group, setGroup] = useState(initialGroup)
  const [sensitivity, setSensitivity] = useState(initialSensitivity)
  const save = useMutation({
    mutationFn: () =>
      reviseObjectProposal(proposal, {
        ...proposal.payload,
        accessGroup: group === '' ? null : group,
        properties: properties.map((property, index) => ({
          ...property,
          sensitivity: sensitivity[index],
        })),
      }),
    onSuccess: () => client.invalidateQueries({ queryKey: ['proposals'] }),
  })
  const unchanged =
    group === initialGroup &&
    sensitivity.every((value, index) => value === initialSensitivity[index])
  const conflict =
    save.error instanceof ApiError && save.error.kind === 'conflict'
  const groups: string[] = [...selectableAccessGroups]
  if (initialGroup && !groups.includes(initialGroup)) groups.push(initialGroup)

  return (
    <form
      aria-label="조회 그룹과 민감도 고치기"
      className="mt-4 space-y-3 rounded-lg border border-slate-200 p-4 text-sm"
      onSubmit={(event) => {
        event.preventDefault()
        save.mutate()
      }}
    >
      <h4 className="font-semibold">조회 그룹과 민감도 고치기</h4>
      <label className="flex flex-col gap-1 font-medium">
        조회 그룹
        <select
          value={group}
          onChange={(event) => {
            setGroup(event.target.value)
            save.reset()
          }}
        >
          <option value="">비공개 (그룹 없음)</option>
          {groups.map((value) => (
            <option key={value} value={value}>
              {accessGroupLabels[value] ?? value} ({value})
            </option>
          ))}
        </select>
      </label>
      {properties.length > 0 && (
        <fieldset className="space-y-1">
          <legend className="font-medium">속성 민감도</legend>
          {properties.map((property, index) => {
            const identifier = property.isIdentifier === true
            // 대표 속성을 민감으로 두면 승인 때 대표 지정이 빠지고 표시 이름이 원천 키 값이 된다
            const display =
              proposal.payload.displayPropertyCode === property.code
            const locked = identifier || display
            return (
              <label
                key={`${index}:${String(property.code)}`}
                className="flex flex-wrap items-center gap-2"
              >
                <span className="min-w-40">
                  {String(property.name ?? property.code)}{' '}
                  <span className="font-mono text-xs text-slate-500">
                    {String(property.code)}
                  </span>
                </span>
                <select
                  aria-label={`${String(property.code)} 민감도`}
                  value={sensitivity[index]}
                  onChange={(event) => {
                    const next = [...sensitivity]
                    next[index] = event.target.value as Sensitivity
                    setSensitivity(next)
                    save.reset()
                  }}
                >
                  <option value="NORMAL">일반</option>
                  <option value="RESTRICTED" disabled={locked}>
                    제한(민감)
                  </option>
                </select>
                {locked && (
                  <span className="text-xs text-slate-500">
                    {identifier ? '식별 속성' : '대표 속성'}은 제한(민감)으로 둘
                    수 없습니다
                  </span>
                )}
              </label>
            )
          })}
        </fieldset>
      )}
      <p className="text-slate-500">
        저장하면 새 개정본이 생깁니다. 원천 데이터 정책의 민감 컬럼은 일반으로
        두어도 반영할 때 제한(민감)으로 올라갑니다. 연결 후보의 비교 조건에 쓰는
        속성을 제한(민감)으로 바꾸면 그 연결 후보는 승인할 수 없습니다.
      </p>
      {save.isError && (
        <ErrorState
          error={save.error}
          title={
            conflict
              ? '그사이 이 후보가 바뀌었습니다. 제안을 새로 조회한 뒤 다시 고쳐 주세요.'
              : undefined
          }
        />
      )}
      {save.isSuccess && (
        <p role="status">개정본을 저장했습니다(개정 {save.data.revision}).</p>
      )}
      <Button type="submit" pending={save.isPending} disabled={unchanged}>
        개정본으로 저장
      </Button>
    </form>
  )
}
