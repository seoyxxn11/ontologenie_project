import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useRef, useState } from 'react'
import { Link, useParams, useLocation } from 'react-router'
import { ApiError } from '../../api/errors'
import Button from '../../components/Button'
import PageHeader from '../../components/PageHeader'
import { EmptyState, ErrorState, LoadingState } from '../../components/Feedback'
import { fetchRuleForEdit, saveRule } from './api'
import {
  buildRule,
  buildTransform,
  emptyCondition,
  readRuleDraft,
  type ConditionDraft,
  type RuleDraft,
  transformLabels,
} from './ruleDraft'
import {
  fetchRuleRelations,
  fetchRuleEndpoint,
  matchesRuleProperty,
} from './ruleCatalog'
import type { RuleEndpoint } from './ruleCatalog'
import RuleSelect from './RuleSelect'
import TransformEditor from './TransformEditor'
import type { Proposal, ProposalOption } from './types'
import './ruleEditor.css'

function RuleForm({
  initial,
  proposal,
  options = [],
  tab,
  onTabChange,
}: {
  initial: RuleDraft
  proposal?: Proposal
  options?: ProposalOption[]
  tab: 'basic' | 'conditions'
  onTabChange: (tab: 'basic' | 'conditions') => void
}) {
  const [draft, setDraft] = useState(initial)
  const [selectedCondition, setSelectedCondition] = useState(0)
  const [candidateMode, setCandidateMode] = useState(
    !initial.linkTypeId && !!initial.linkTypeCode,
  )
  const { search } = useLocation()
  const relations = useQuery({
    queryKey: ['rule-editor', 'relations'],
    queryFn: ({ signal }) => fetchRuleRelations(signal),
    retry: false,
    refetchOnMount: 'always',
    refetchOnWindowFocus: false,
  })
  const relation = relations.data?.find(
    (item) => String(item.id) === draft.linkTypeId,
  )
  const endpoints = useQuery({
    queryKey: [
      'rule-editor',
      'endpoints',
      relation?.id,
      relation?.sourceTypeId,
      relation?.targetTypeId,
      relation?.rowVersion,
    ],
    queryFn: async ({ signal }) => {
      const [source, target] = await Promise.all([
        fetchRuleEndpoint(relation!.sourceTypeId, signal),
        fetchRuleEndpoint(relation!.targetTypeId, signal),
      ])
      return { source, target }
    },
    enabled: !!relation,
    retry: false,
    refetchOnMount: 'always',
    refetchOnWindowFocus: false,
  })
  const catalogReady =
    candidateMode ||
    (!draft.linkTypeId
      ? false
      : !!relation &&
        relations.isSuccess &&
        !relations.isFetching &&
        endpoints.isSuccess &&
        !endpoints.isFetching)
  function propertyOptions(endpoint?: RuleEndpoint) {
    return (endpoint?.properties ?? [])
      .filter((property) => property.sensitivity !== 'RESTRICTED')
      .map((property) => ({
        value: `${endpoint!.code}.${property.code}`,
        name: property.name,
        code: property.code,
      }))
  }
  function propertyValue(value: string, endpoint?: RuleEndpoint) {
    return endpoint?.properties.some((property) => property.code === value)
      ? `${endpoint.code}.${value}`
      : value
  }
  const [validation, setValidation] = useState('')
  const lock = useRef(false)
  const client = useQueryClient()
  const mutation = useMutation({
    mutationFn: saveRule,
    retry: false,
    onSuccess: () => {
      void client.invalidateQueries({
        queryKey: ['proposals'],
        refetchType: 'none',
      })
    },
  })
  const blocked =
    mutation.error instanceof ApiError &&
    ([401, 403, 404, 409].includes(mutation.error.status ?? 0) ||
      ['network', 'aborted', 'invalid-response', 'server'].includes(
        mutation.error.kind,
      ))
  function changeCondition(index: number, update: Partial<ConditionDraft>) {
    setDraft({
      ...draft,
      conditions: draft.conditions.map((condition, position) =>
        position === index ? { ...condition, ...update } : condition,
      ),
    })
  }
  async function submit() {
    if (lock.current || blocked || mutation.isSuccess) return
    let rule
    try {
      rule = buildRule(draft, proposal)
      if (!catalogReady)
        throw new Error('관계 유형과 속성 목록을 확인한 뒤 저장해 주세요.')
      if (draft.linkTypeId && endpoints.data) {
        for (const [index, condition] of draft.conditions.entries()) {
          for (const side of ['source', 'target'] as const) {
            const value = condition[`${side}Property`]
            const originalValue = condition.original[`${side}Property`]
            const preservedCandidate =
              proposal?.analysisRunId != null &&
              draft.linkTypeId === initial.linkTypeId &&
              value === originalValue
            if (
              !matchesRuleProperty(value, endpoints.data[side]) &&
              !preservedCandidate
            )
              throw new Error(
                `조건 ${index + 1}의 ${side === 'source' ? '출발' : '도착'} 속성을 선택한 관계에 맞게 다시 선택해 주세요.`,
              )
          }
        }
      }
    } catch (error) {
      const conditionIndex = draft.conditions.findIndex(
        (condition) =>
          !condition.sourceProperty.trim() ||
          !condition.targetProperty.trim() ||
          (() => {
            try {
              ;[
                ...condition.sourceTransforms,
                ...condition.targetTransforms,
              ].forEach(buildTransform)
              return false
            } catch {
              return true
            }
          })(),
      )
      if (draft.linkTypeId || draft.linkTypeCode) {
        onTabChange('conditions')
        if (conditionIndex >= 0) setSelectedCondition(conditionIndex)
      } else onTabChange('basic')
      setValidation(
        error instanceof Error ? error.message : '입력을 확인해 주세요.',
      )
      return
    }
    setValidation('')
    lock.current = true
    try {
      await mutation.mutateAsync({
        ...rule,
        id: proposal?.id,
        baseRevision: proposal?.revision,
        options,
      })
    } catch {
      /* 공통 오류 안내로 표시한다. */
    } finally {
      lock.current = false
    }
  }
  if (mutation.isSuccess)
    return (
      <section
        role="status"
        className="space-y-3 rounded-xl border border-emerald-200 bg-emerald-50 p-5"
      >
        <h2 className="text-lg font-semibold">규칙 후보를 저장했습니다.</h2>
        <p>
          제안 #{mutation.data.id} · 개정 {mutation.data.revision}
        </p>
        <p>
          재검사가 필요합니다. 검사 #{mutation.data.checkRunId}가 자동으로
          요청되었습니다. 이전 개정의 검사 결과로 승인할 수 없습니다.
        </p>
        <Link
          className="text-violet-700 underline"
          to={`/admin/rules/${mutation.data.id}${search}`}
        >
          저장한 제안 보기
        </Link>
      </section>
    )
  return (
    <form
      className="rules-editor-form space-y-5"
      onSubmit={(event) => {
        event.preventDefault()
        void submit()
      }}
    >
      {proposal && (
        <p className="text-sm text-slate-500">
          제안 #{proposal.id} · 수정 기준 개정 {proposal.revision} · 기존 복수
          후보 {options.length}건을 함께 보존합니다.
        </p>
      )}
      <fieldset
        disabled={mutation.isPending || blocked}
        className="space-y-5 disabled:opacity-60"
      >
        <section
          hidden={tab !== 'basic'}
          aria-label="기본 정보"
          className="rule-editor-basic space-y-5"
        >
          <div className="grid gap-4 sm:grid-cols-2">
            {(
              [
                { key: 'name', label: '규칙명' },
                { key: 'code', label: '규칙 코드' },
              ] as const
            ).map((field) => (
              <label key={field.key} className="flex flex-col gap-2 text-sm">
                {field.label}
                <input
                  className="rounded-lg border border-slate-300 p-2"
                  value={draft[field.key]}
                  onChange={(event) =>
                    setDraft({ ...draft, [field.key]: event.target.value })
                  }
                />
              </label>
            ))}
          </div>
          {proposal?.analysisRunId != null && (
            <label className="flex flex-col gap-2 text-sm">
              관계 지정 방식
              <select
                className="rounded-lg border p-2"
                value={candidateMode ? 'candidate' : 'existing'}
                onChange={(event) => {
                  const candidate = event.target.value === 'candidate'
                  setCandidateMode(candidate)
                  setDraft({
                    ...draft,
                    linkTypeId: '',
                    linkTypeCode: candidate ? initial.linkTypeCode : '',
                  })
                }}
              >
                <option value="existing">등록된 관계 유형 선택</option>
                <option value="candidate">
                  동일 분석의 관계 유형 코드 지정
                </option>
              </select>
            </label>
          )}
          {candidateMode ? (
            <label className="flex flex-col gap-2 text-sm">
              관계 유형 코드
              <input
                className="rounded-lg border p-2"
                aria-label="관계 유형 코드"
                value={draft.linkTypeCode}
                onChange={(event) =>
                  setDraft({ ...draft, linkTypeCode: event.target.value })
                }
              />
              <span>
                동일 분석의 미확정 관계·속성 코드는 서버가 최종 검사합니다.
              </span>
            </label>
          ) : (
            <>
              <div className="rule-editor-relation">
                <RuleSelect
                  label="관계 유형"
                  value={draft.linkTypeId}
                  options={(relations.data ?? []).map((item) => ({
                    value: String(item.id),
                    name: item.name,
                    code: item.code,
                  }))}
                  disabled={
                    relations.isPending ||
                    relations.isFetching ||
                    relations.isError
                  }
                  onChange={(linkTypeId) => {
                    const changed = linkTypeId !== draft.linkTypeId
                    setDraft({
                      ...draft,
                      linkTypeId,
                      linkTypeCode: '',
                      conditions: draft.conditions.map((condition) =>
                        changed
                          ? {
                              ...condition,
                              sourceProperty: '',
                              targetProperty: '',
                            }
                          : condition,
                      ),
                    })
                    setValidation(
                      changed
                        ? '관계 유형을 변경했습니다. 출발·도착 속성을 다시 선택해 주세요. 변환 단계는 유지됩니다.'
                        : '',
                    )
                  }}
                />
              </div>
              {relations.isPending && (
                <LoadingState message="관계 유형을 불러오는 중…" />
              )}
              {relations.isError && (
                <ErrorState
                  error={relations.error}
                  onRetry={() => {
                    void relations.refetch()
                  }}
                />
              )}
              {draft.linkTypeId && relations.isSuccess && !relation && (
                <p role="alert">
                  기존 관계 유형을 찾을 수 없습니다. 최신 목록에서 다시 선택해
                  주세요.
                </p>
              )}
              {relation && (
                <>
                  {(endpoints.isPending || endpoints.isFetching) && (
                    <LoadingState message="출발·도착 속성을 불러오는 중…" />
                  )}
                  {endpoints.isError && (
                    <ErrorState
                      error={endpoints.error}
                      onRetry={() => {
                        void endpoints.refetch()
                      }}
                    />
                  )}
                  {endpoints.data && (
                    <p>
                      출발: {endpoints.data.source.name} (
                      {endpoints.data.source.code}) · 도착:{' '}
                      {endpoints.data.target.name} ({endpoints.data.target.code}
                      )
                    </p>
                  )}
                </>
              )}
            </>
          )}
          <details className="rule-editor-description">
            <summary>설명·제안 근거</summary>
            <div className="rule-editor-description-fields">
              <label className="flex flex-col gap-2 text-sm">
                설명
                <textarea
                  className="rounded-lg border p-2"
                  value={draft.description}
                  onChange={(event) =>
                    setDraft({ ...draft, description: event.target.value })
                  }
                />
              </label>
              <label className="flex flex-col gap-2 text-sm">
                제안 근거
                <textarea
                  className="rounded-lg border p-2"
                  value={draft.reason}
                  onChange={(event) =>
                    setDraft({ ...draft, reason: event.target.value })
                  }
                />
              </label>
            </div>
          </details>
        </section>
        <section
          hidden={tab !== 'conditions'}
          aria-label="연결 조건"
          className="rule-editor-conditions"
        >
          {!candidateMode && !draft.linkTypeId && (
            <div role="status" className="rule-editor-prerequisite">
              <span>
                관계 유형을 먼저 선택하면 출발·도착 속성을 고를 수 있습니다.
              </span>
              <Button variant="secondary" onClick={() => onTabChange('basic')}>
                관계 유형 선택
              </Button>
            </div>
          )}
          <div className="rule-editor-condition-toolbar">
            <div
              role="group"
              aria-label="조건 선택"
              className="rule-editor-condition-switch"
            >
              {draft.conditions.length > 1 &&
                draft.conditions.map((_, index) => (
                  <Button
                    key={index}
                    variant="secondary"
                    aria-pressed={selectedCondition === index}
                    onClick={() => setSelectedCondition(index)}
                  >
                    조건 {index + 1}
                  </Button>
                ))}
              <Button
                variant="secondary"
                onClick={() => {
                  setSelectedCondition(draft.conditions.length)
                  setDraft({
                    ...draft,
                    conditions: [...draft.conditions, emptyCondition()],
                  })
                }}
              >
                조건 추가
              </Button>
            </div>
            <p className="rule-editor-help">
              {draft.conditions.length > 1
                ? '모든 조건을 만족해야 연결합니다.'
                : '각 속성의 변환은 위에서 아래 순서로 적용합니다.'}
            </p>
          </div>
          {draft.conditions.map((condition, index) => (
            <fieldset
              key={index}
              hidden={selectedCondition !== index}
              className="rule-editor-condition"
            >
              <legend className="sr-only">조건 {index + 1}</legend>
              <div className="rule-editor-condition-heading">
                <span>{draft.conditions.length === 1 ? '조건 1' : ''}</span>
                <Button
                  variant="secondary"
                  disabled={draft.conditions.length === 1}
                  onClick={() => {
                    setDraft({
                      ...draft,
                      conditions: draft.conditions.filter(
                        (_, position) => position !== index,
                      ),
                    })
                    setSelectedCondition(
                      Math.min(index, draft.conditions.length - 2),
                    )
                  }}
                >
                  조건 삭제
                </Button>
              </div>
              <div className="rule-editor-lanes">
                {(['source', 'target'] as const).map((side) => {
                  const label = side === 'source' ? '출발' : '도착'
                  const endpoint = endpoints.data?.[side]
                  return (
                    <div key={side} className="rule-editor-side">
                      <h2>
                        {label}
                        {endpoint ? ' · ' + endpoint.name : ''}
                      </h2>
                      {candidateMode ? (
                        <label className="flex flex-col gap-2 text-sm">
                          {label} 속성 코드
                          <input
                            className="rounded border p-2"
                            value={condition[`${side}Property`]}
                            onChange={(event) =>
                              changeCondition(index, {
                                [`${side}Property`]: event.target.value,
                              })
                            }
                          />
                        </label>
                      ) : (
                        <RuleSelect
                          label={label + ' 속성'}
                          value={propertyValue(
                            condition[`${side}Property`],
                            endpoint,
                          )}
                          options={propertyOptions(endpoint)}
                          disabled={!catalogReady || !draft.linkTypeId}
                          onChange={(value) =>
                            changeCondition(index, {
                              [`${side}Property`]: value,
                            })
                          }
                        />
                      )}
                      <TransformEditor
                        searchableFunctions
                        label={label + ' 값 변환'}
                        steps={condition[`${side}Transforms`]}
                        onChange={(steps) =>
                          changeCondition(index, {
                            [`${side}Transforms`]: steps,
                          })
                        }
                      />
                    </div>
                  )
                })}
                <div className="rule-editor-equal">
                  <strong>=</strong>
                  <span>같음</span>
                  <span className="rule-editor-help">변환 후 비교</span>
                </div>
              </div>
              <p className="rule-editor-meaning">
                {(['source', 'target'] as const)
                  .map((side) => {
                    const endpoint = endpoints.data?.[side]
                    const property = propertyOptions(endpoint).find(
                      (item) =>
                        item.value ===
                        propertyValue(condition[`${side}Property`], endpoint),
                    )
                    const steps = condition[`${side}Transforms`]
                    return (
                      (side === 'source' ? '출발: ' : '도착: ') +
                      (property?.name ??
                        (condition[`${side}Property`] || '속성 미선택')) +
                      (steps.length
                        ? ' → ' +
                          steps
                            .map(
                              (step) =>
                                (transformLabels[step.op] ?? step.op) +
                                (step.arg ? ' (' + step.arg + ')' : ''),
                            )
                            .join(' → ')
                        : ' (원래 값)')
                    )
                  })
                  .join(' / ')}{' '}
                · 두 결과가 같으면 연결합니다.
              </p>
            </fieldset>
          ))}
        </section>
      </fieldset>
      {validation && (
        <p role="alert" className="text-sm text-red-700">
          {validation}
        </p>
      )}
      {mutation.isError && (
        <>
          <ErrorState error={mutation.error} />
          {blocked && (
            <p role="status">
              저장을 중단했습니다. 입력은 유지됩니다. 목록에서 최신 상태와 저장
              여부를 확인한 뒤 다시 편집해 주세요.
            </p>
          )}
          {mutation.error instanceof ApiError &&
            mutation.error.status === 409 && (
              <p className="text-sm">
                다른 개정 또는 결정 상태와 충돌했습니다. 목록에서 최신 상태를
                확인한 뒤 다시 편집해 주세요. 입력은 현재 화면에 보존되어
                있습니다.
              </p>
            )}
        </>
      )}
      <div className="rule-editor-footer">
        <p className="rule-editor-help">
          저장하면 새 개정의 정합성 검사를 자동 요청합니다. 규칙 승인·반영은
          별도 절차입니다. 통신 오류가 나면 목록에서 저장 여부를 확인한 뒤 다시
          시도하세요.
        </p>
        <Button
          type="submit"
          pending={mutation.isPending}
          disabled={blocked || (!!draft.linkTypeId && !catalogReady)}
        >
          후보 저장
        </Button>
      </div>
    </form>
  )
}

export default function RuleEditorPage() {
  const { id } = useParams()
  const [tab, setTab] = useState<'basic' | 'conditions'>('basic')
  const { search } = useLocation()
  const editing = id !== undefined
  const number = Number(id)
  const valid =
    !editing ||
    (/^\d+$/.test(id ?? '') && Number.isSafeInteger(number) && number > 0)
  const query = useQuery({
    queryKey: ['proposals', 'editor', number],
    queryFn: ({ signal }) => fetchRuleForEdit(number, signal),
    enabled: editing && valid,
    retry: false,
    refetchOnMount: 'always',
    refetchOnWindowFocus: false,
    refetchOnReconnect: false,
  })
  let content
  if (!valid) content = <EmptyState message="올바른 제안 주소가 아닙니다." />
  else if (editing && (query.isPending || query.isFetching || query.isPaused))
    content = <LoadingState message="규칙 후보를 불러오는 중…" />
  else if (editing && query.isError)
    content = (
      <ErrorState
        error={query.error}
        onRetry={() => {
          void query.refetch()
        }}
      />
    )
  else {
    const item = editing ? query.data?.proposal : undefined
    if (
      item &&
      (item.itemKind !== 'LINK_RULE' ||
        !['PENDING', 'HELD'].includes(item.reviewStatus))
    )
      content = (
        <EmptyState message="검토 대기·보류 상태의 연결 규칙 후보만 수정할 수 있습니다." />
      )
    else {
      let initial: RuleDraft | undefined
      let error: Error | undefined
      try {
        initial = readRuleDraft(item)
      } catch (cause) {
        error =
          cause instanceof Error
            ? cause
            : new Error('규칙 형식을 확인해 주세요.')
      }
      content = initial ? (
        <RuleForm
          key={`${item?.id ?? 'new'}:${item?.revision ?? 0}`}
          initial={initial}
          tab={tab}
          onTabChange={setTab}
          proposal={item}
          options={query.data?.options}
        />
      ) : (
        <ErrorState error={error!} />
      )
    }
  }
  return (
    <div className="rules-editor-page rule-editor-tabs-page">
      <div className="rule-editor-navigation">
        <Link
          className="rule-editor-back"
          aria-label="연결 규칙 목록으로 돌아가기"
          data-tooltip="연결 규칙 목록으로 돌아가기"
          to={`/admin/rules${search}`}
        >
          <svg
            width="20"
            height="20"
            viewBox="0 0 24 24"
            fill="none"
            stroke="currentColor"
            strokeWidth="1.8"
            aria-hidden="true"
          >
            <path d="m12 5-7 7 7 7M5 12h14" />
          </svg>
        </Link>
        <span className="rule-editor-divider" aria-hidden="true" />
        <div
          role="group"
          aria-label="규칙 편집 영역"
          className="management-tabs"
        >
          <button
            type="button"
            aria-pressed={tab === 'basic'}
            onClick={() => setTab('basic')}
          >
            기본 정보
          </button>
          <button
            type="button"
            aria-pressed={tab === 'conditions'}
            onClick={() => setTab('conditions')}
          >
            연결 조건
          </button>
        </div>
      </div>
      <PageHeader
        title={editing ? '규칙 후보 수정' : '규칙 후보 추가'}
        description="연결 조건과 변환 순서를 입력하고 검토할 규칙 후보로 저장하세요."
      />
      {content}
    </div>
  )
}
