import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useRef, useState } from 'react'
import { Link, useParams } from 'react-router'
import { ApiError } from '../../api/errors'
import Button from '../../components/Button'
import { EmptyState, ErrorState, LoadingState } from '../../components/Feedback'
import { fetchRuleForEdit, saveRule } from './api'
import {
  buildRule,
  emptyCondition,
  readRuleDraft,
  type ConditionDraft,
  type RuleDraft,
} from './ruleDraft'
import TransformEditor from './TransformEditor'
import type { Proposal, ProposalOption } from './types'

function RuleForm({
  initial,
  proposal,
  options = [],
}: {
  initial: RuleDraft
  proposal?: Proposal
  options?: ProposalOption[]
}) {
  const [draft, setDraft] = useState(initial)
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
    [401, 403, 404, 409].includes(mutation.error.status ?? 0)
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
    } catch (error) {
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
          to={`/admin/rules/${mutation.data.id}`}
        >
          저장한 제안 보기
        </Link>
      </section>
    )
  return (
    <form
      className="space-y-5"
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
        <div className="grid gap-4 sm:grid-cols-2">
          {(
            [
              { key: 'name', label: '규칙명' },
              { key: 'code', label: '규칙 코드' },
              { key: 'linkTypeId', label: '관계 유형 ID' },
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
                inputMode={field.key === 'linkTypeId' ? 'numeric' : undefined}
              />
            </label>
          ))}
          {proposal?.analysisRunId != null && (
            <label className="flex flex-col gap-2 text-sm">
              관계 유형 코드
              <input
                className="rounded-lg border p-2"
                value={draft.linkTypeCode}
                onChange={(event) =>
                  setDraft({ ...draft, linkTypeCode: event.target.value })
                }
              />
              <span className="text-xs text-slate-500">
                아직 확정되지 않은 동일 분석의 관계 유형 후보는 코드로 지정할 수
                있습니다.
              </span>
            </label>
          )}
        </div>
        <p className="text-sm text-slate-500">
          기존 관계 유형 ID와 그 관계의 출발·도착 속성 코드를 지정하세요. 속성이
          해당 관계에 속하는지는 서버가 검사합니다.
        </p>
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
        <section className="space-y-4">
          <h2 className="text-lg font-semibold">비교 조건</h2>
          <p className="text-sm text-slate-500">
            비교 연산은 같음(EQ)이며, 각 속성의 변환은 위에서 아래 순서로
            적용합니다.
          </p>
          {draft.conditions.map((condition, index) => (
            <fieldset
              key={index}
              className="space-y-4 rounded-xl border border-slate-200 p-4"
            >
              <legend className="font-medium">조건 {index + 1}</legend>
              <div className="grid gap-3 sm:grid-cols-2">
                <label className="flex flex-col gap-2 text-sm">
                  출발 속성 코드
                  <input
                    className="rounded border p-2"
                    value={condition.sourceProperty}
                    onChange={(event) =>
                      changeCondition(index, {
                        sourceProperty: event.target.value,
                      })
                    }
                    placeholder="예: WORK_ORDER.equipment_code"
                  />
                </label>
                <label className="flex flex-col gap-2 text-sm">
                  도착 속성 코드
                  <input
                    className="rounded border p-2"
                    value={condition.targetProperty}
                    onChange={(event) =>
                      changeCondition(index, {
                        targetProperty: event.target.value,
                      })
                    }
                    placeholder="예: EQUIPMENT.code"
                  />
                </label>
              </div>
              <div className="grid gap-4 lg:grid-cols-2">
                <TransformEditor
                  label="출발 값 변환"
                  steps={condition.sourceTransforms}
                  onChange={(sourceTransforms) =>
                    changeCondition(index, { sourceTransforms })
                  }
                />
                <TransformEditor
                  label="도착 값 변환"
                  steps={condition.targetTransforms}
                  onChange={(targetTransforms) =>
                    changeCondition(index, { targetTransforms })
                  }
                />
              </div>
              <Button
                variant="secondary"
                disabled={draft.conditions.length === 1}
                onClick={() =>
                  setDraft({
                    ...draft,
                    conditions: draft.conditions.filter(
                      (_, position) => position !== index,
                    ),
                  })
                }
              >
                조건 삭제
              </Button>
            </fieldset>
          ))}
          <Button
            variant="secondary"
            onClick={() =>
              setDraft({
                ...draft,
                conditions: [...draft.conditions, emptyCondition()],
              })
            }
          >
            조건 추가
          </Button>
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
      <p className="text-sm text-slate-500">
        저장하면 새 개정의 정합성 검사를 자동 요청합니다. 규칙 승인·반영은 별도
        절차입니다. 통신 오류가 나면 목록에서 저장 여부를 확인한 뒤 다시
        시도하세요.
      </p>
      <Button type="submit" pending={mutation.isPending} disabled={blocked}>
        후보 저장
      </Button>
    </form>
  )
}

export default function RuleEditorPage() {
  const { id } = useParams()
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
          proposal={item}
          options={query.data?.options}
        />
      ) : (
        <ErrorState error={error!} />
      )
    }
  }
  return (
    <div className="space-y-5">
      <Link className="text-sm text-violet-700 underline" to="/admin/rules">
        제안 목록으로 돌아가기
      </Link>
      <h1 className="text-2xl font-semibold">
        {editing ? '규칙 후보 수정' : '규칙 후보 추가'}
      </h1>
      {content}
    </div>
  )
}
