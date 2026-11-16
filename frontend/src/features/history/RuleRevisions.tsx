import { formatKstDateTime } from '../../utils/dateTime'
import { useQuery } from '@tanstack/react-query'
import { useState } from 'react'
import Button from '../../components/Button'
import DataTable from '../../components/DataTable'
import StatusBadge from '../../components/StatusBadge'
import ExecutionHistory from '../audit/ExecutionHistory'
import { fetchRuleRevisions, fetchRules } from './api'
import { ruleOriginLabels, ruleStatusLabels } from './labels'
import type { RuleCondition, RuleRevision, RuleSummary } from './types'

function formatDateTime(value?: string | null) {
  return value ? formatKstDateTime(value) : '-'
}

function formatTransforms(transforms: Record<string, unknown>[]) {
  return transforms.length > 0
    ? ` (변환 ${transforms.length}단계: ${transforms.map((step) => JSON.stringify(step)).join(' → ')})`
    : ''
}

// 속성은 이름이 오면 "설비 코드(#31)"처럼, 아직 번호만 오면 "속성 #31"로 보여 준다.
function propertyLabel(id: number, name?: string | null) {
  return name ? `${name}(#${id})` : `속성 #${id}`
}

// 비교 조건은 번호 순서대로 AND로 묶인다.
function formatConditions(conditions: RuleCondition[]) {
  if (conditions.length === 0) {
    return '-'
  }
  return [...conditions]
    .sort((a, b) => a.sequenceNo - b.sequenceNo)
    .map(
      (condition) =>
        `${condition.sequenceNo}. 출발 ${propertyLabel(condition.sourcePropertyId, condition.sourcePropertyName)}${formatTransforms(condition.sourceTransforms)} = 도착 ${propertyLabel(condition.targetPropertyId, condition.targetPropertyName)}${formatTransforms(condition.targetTransforms)}`,
    )
    .join('\n')
}

function formatFilter(filter: Record<string, unknown>) {
  return Object.keys(filter).length > 0
    ? JSON.stringify(filter, null, 2)
    : '없음'
}

// 비교 표의 한 줄: 항목 이름과 개정본에서 그 값을 꺼내는 방법.
// 규칙 내용이 아닌 기록 정보(만든 시각 등)는 개정마다 늘 달라서 "바뀜"을 붙이지 않는다.
const comparisonRows: {
  label: string
  value: (r: RuleRevision) => string
  record?: boolean
}[] = [
  { label: '상태', value: (r) => ruleStatusLabels[r.status] },
  { label: '설명', value: (r) => r.description ?? '-' },
  { label: '비교 조건', value: (r) => formatConditions(r.conditions) },
  { label: '출발 조건', value: (r) => formatFilter(r.sourceFilter) },
  { label: '도착 조건', value: (r) => formatFilter(r.targetFilter) },
  { label: '만든 방법', value: (r) => ruleOriginLabels[r.origin] },
  {
    label: '원본 제안',
    value: (r) => (r.proposalItemId ? `제안 #${r.proposalItemId}` : '-'),
    record: true,
  },
  {
    label: '만든 시각',
    value: (r) => formatDateTime(r.createdAt),
    record: true,
  },
  {
    label: '만든 사람',
    value: (r) => (r.createdBy ? `사용자 #${r.createdBy}` : '-'),
    record: true,
  },
  {
    label: '퇴역 시각',
    value: (r) => formatDateTime(r.retiredAt),
    record: true,
  },
]

type RevisionComparisonProps = {
  previous?: RuleRevision
  selected: RuleRevision
}

// 고른 개정본을 바로 앞 개정본과 나란히 놓고, 값이 달라진 항목에 "바뀜"을 붙인다.
function RevisionComparison({ previous, selected }: RevisionComparisonProps) {
  return (
    <div className="mt-4">
      <DataTable label="규칙 개정 비교" className="table-fixed min-w-[640px]">
        <thead className="bg-slate-50 text-sm text-slate-500">
          <tr>
            <th className="w-[16%] px-3 py-2 font-normal">항목</th>
            {previous && (
              <th className="px-3 py-2 font-normal">
                {`이전 · 개정 ${previous.revision}`}
              </th>
            )}
            <th className="px-3 py-2 font-normal">
              {`선택 · 개정 ${selected.revision}`}
            </th>
          </tr>
        </thead>
        <tbody className="divide-y divide-slate-100 align-top">
          {comparisonRows.map((row) => {
            const after = row.value(selected)
            const changed =
              previous && !row.record ? row.value(previous) !== after : false
            return (
              <tr key={row.label}>
                <td className="px-3 py-2 text-slate-600">
                  {row.label}
                  {changed && (
                    <span className="ml-1">
                      <StatusBadge tone="warning">바뀜</StatusBadge>
                    </span>
                  )}
                </td>
                {previous && (
                  <td className="px-3 py-2 whitespace-pre-wrap break-all text-slate-500">
                    {row.value(previous)}
                  </td>
                )}
                <td
                  className={`px-3 py-2 whitespace-pre-wrap break-all ${changed ? 'font-semibold text-slate-900' : ''}`}
                >
                  {after}
                </td>
              </tr>
            )
          })}
        </tbody>
      </DataTable>
      {!previous && (
        <p className="mt-2 text-sm text-slate-500">
          이 개정본에는 비교할 이전 개정본이 없습니다.
        </p>
      )}
      <p className="mt-2 text-sm text-slate-500">
        속성·관계 유형 이름은 개정 당시가 아니라 지금 이름입니다.
      </p>
    </div>
  )
}

type RevisionActivityProps = {
  revision: RuleRevision
}

// 개정본의 원본 제안(proposal_item)에 대한 실행 기록(승인·거절·검사 등, 096).
// 직접 등록·초기 데이터 개정본은 원본 제안이 없어 보여 줄 기록이 없다.
function RevisionActivity({ revision }: RevisionActivityProps) {
  if (!revision.proposalItemId) {
    return (
      <p className="mt-4 text-sm text-slate-500">
        이 개정본은 원본 제안이 없어(직접 등록·초기 데이터) 연결된 활동 기록이
        없습니다.
      </p>
    )
  }
  return (
    <div className="mt-4">
      <ExecutionHistory
        key={revision.proposalItemId}
        target={{ type: 'proposal_item', id: revision.proposalItemId }}
        targetLabel={`제안 #${revision.proposalItemId}`}
      />
    </div>
  )
}

const historyViews = [
  { key: 'revisions', label: '개정 내역' },
  { key: 'activity', label: '활동 기록' },
] as const

type RevisionListProps = {
  revisions: RuleRevision[]
}

function RevisionList({ revisions }: RevisionListProps) {
  const sorted = [...revisions].sort((a, b) => b.revision - a.revision)
  const initial =
    sorted.find((revision) => revision.status === 'ACTIVE') ?? sorted[0]
  const [selectedRevision, setSelectedRevision] = useState(initial.revision)
  const selected =
    sorted.find((revision) => revision.revision === selectedRevision) ?? initial
  const previous = sorted.find(
    (revision) => revision.revision < selected.revision,
  )
  const [view, setView] =
    useState<(typeof historyViews)[number]['key']>('revisions')

  return (
    <div>
      <p className="text-sm text-slate-500">
        {`${selected.code} · ${selected.linkTypeName ?? `관계 유형 #${selected.linkTypeId}`}`}
      </p>
      <h3 className="mt-1 text-lg font-semibold text-slate-900">
        {initial.description ?? selected.code}
      </h3>
      <div
        role="group"
        aria-label="개정본"
        className="management-selection mt-3"
      >
        {sorted.map((revision) => (
          <button
            key={revision.id}
            type="button"
            aria-pressed={revision.revision === selected.revision}
            onClick={() => setSelectedRevision(revision.revision)}
          >
            {`개정 ${revision.revision} · ${ruleStatusLabels[revision.status]}`}
          </button>
        ))}
      </div>
      <div
        role="group"
        aria-label="규칙 이력 보기"
        className="management-tabs mt-4"
      >
        {historyViews.map((item) => (
          <button
            key={item.key}
            type="button"
            aria-pressed={view === item.key}
            onClick={() => setView(item.key)}
          >
            {item.label}
          </button>
        ))}
      </div>
      {view === 'revisions' ? (
        <RevisionComparison previous={previous} selected={selected} />
      ) : (
        <RevisionActivity revision={selected} />
      )}
    </div>
  )
}

function ruleOptionLabel(rule: RuleSummary) {
  return `${rule.description ?? rule.code} · ${rule.linkTypeName} · 개정 ${rule.revision} ${ruleStatusLabels[rule.status]}`
}

type RulePickerProps = {
  code: string
  onSearch: (code: string) => void
}

// 규칙 목록 API로 규칙을 고르게 한다. 목록을 받지 못하면(아직 백엔드에 없는 경우 등) 코드를 직접 입력한다.
function RulePicker({ code, onSearch }: RulePickerProps) {
  const [input, setInput] = useState(code)
  const {
    data: rules,
    isPending,
    isError,
  } = useQuery({
    queryKey: ['ontology', 'rules'],
    queryFn: fetchRules,
    retry: false,
  })

  if (isPending) {
    return <p className="text-sm text-slate-500">규칙 목록을 불러오는 중…</p>
  }

  if (!isError) {
    // 주소로 들어온 코드가 목록에 없어도 선택된 값으로 보이게 한다.
    const options =
      code && !rules.some((rule) => rule.code === code)
        ? [{ value: code, label: code }]
        : []
    return (
      <label className="flex flex-col gap-1 text-sm">
        규칙 선택
        <select
          value={code}
          onChange={(event) => onSearch(event.target.value)}
          className="rounded-lg border border-slate-300 px-2 py-1"
        >
          <option value="">규칙을 고르세요</option>
          {rules.map((rule) => (
            <option key={rule.code} value={rule.code}>
              {ruleOptionLabel(rule)}
            </option>
          ))}
          {options.map((option) => (
            <option key={option.value} value={option.value}>
              {option.label}
            </option>
          ))}
        </select>
      </label>
    )
  }

  return (
    <form
      className="management-filter flex items-end gap-3 text-sm"
      onSubmit={(event) => {
        event.preventDefault()
        onSearch(input.trim())
      }}
    >
      <label className="flex flex-1 flex-col gap-1">
        규칙 코드
        <input
          type="search"
          value={input}
          onChange={(event) => setInput(event.target.value)}
          placeholder="예: WO_EQUIPMENT_CODE"
          className="rounded-lg border border-slate-300 px-2 py-1"
        />
      </label>
      <Button type="submit">조회</Button>
    </form>
  )
}

type RuleRevisionsProps = {
  code: string
  onSearch: (code: string) => void
}

// 046 연결 규칙 개정 이력: 규칙 하나의 개정본들을 고르고 이전 개정본과 비교한다.
function RuleRevisions({ code, onSearch }: RuleRevisionsProps) {
  const {
    data: revisions,
    isPending,
    isError,
    error,
  } = useQuery({
    queryKey: ['ontology', 'rules', code, 'revisions'],
    queryFn: () => fetchRuleRevisions(code),
    enabled: code !== '',
  })

  let content
  if (code === '') {
    content = (
      <p className="text-slate-500">
        규칙을 고르면 그 규칙의 개정 이력을 보여 줍니다.
      </p>
    )
  } else if (isPending) {
    content = <p className="text-slate-500">개정 이력을 불러오는 중…</p>
  } else if (isError) {
    content = <p className="text-red-600">{error.message}</p>
  } else if (revisions.length === 0) {
    content = (
      <p className="text-slate-500">이 규칙 코드의 개정 이력이 없습니다.</p>
    )
  } else {
    content = <RevisionList key={code} revisions={revisions} />
  }

  return (
    <div className="management-detail">
      <RulePicker code={code} onSearch={onSearch} />
      <div className="mt-5">{content}</div>
    </div>
  )
}

export default RuleRevisions
