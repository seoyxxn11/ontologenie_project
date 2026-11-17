import { isRecord } from '../../api/errors'
import type { Proposal } from './types'

export const transformLabels: Record<string, string> = {
  STRIP_PREFIX: '접두어 제거',
  ADD_PREFIX: '접두어 추가',
  PAD_ZEROS: '앞에 0 채우기',
  UPPER: '대문자 변환',
  TRIM: '앞뒤 공백 제거',
  EXTRACT_NUMBER: '숫자 추출',
  TO_INTEGER: '정수 변환',
  TO_DECIMAL: '소수 변환',
  TO_DATE: '날짜 변환',
  MAP_VALUE: '값 대응표',
  MAP_KNOWN: '대응표에 있는 값만 남기기',
}
export type TransformDraft = { op: string; arg: string }
export function toTransformDraft(step: {
  op: string
  arg: unknown
}): TransformDraft {
  return {
    op: step.op,
    arg:
      step.arg == null
        ? ''
        : typeof step.arg === 'string'
          ? step.arg
          : JSON.stringify(step.arg),
  }
}
export type ConditionDraft = {
  sourceProperty: string
  targetProperty: string
  sourceTransforms: TransformDraft[]
  targetTransforms: TransformDraft[]
  original: Record<string, unknown>
}
export type RuleDraft = {
  name: string
  code: string
  description: string
  reason: string
  linkTypeId: string
  linkTypeCode: string
  conditions: ConditionDraft[]
}
export const emptyCondition = (): ConditionDraft => ({
  sourceProperty: '',
  targetProperty: '',
  sourceTransforms: [],
  targetTransforms: [],
  original: {},
})
const noArgs = [
  'UPPER',
  'TRIM',
  'EXTRACT_NUMBER',
  'TO_INTEGER',
  'TO_DECIMAL',
  'TO_DATE',
]

function readTransforms(value: unknown): TransformDraft[] {
  if (value == null) return []
  if (!Array.isArray(value))
    throw new Error('기존 변환 목록을 읽을 수 없어 수정을 중단했습니다.')
  return value.map((step) => {
    if (
      !isRecord(step) ||
      typeof step.op !== 'string' ||
      !Object.hasOwn(transformLabels, step.op)
    )
      throw new Error('지원하지 않는 기존 변환이 있어 수정을 중단했습니다.')
    const draft = toTransformDraft({ op: step.op, arg: step.arg })
    buildTransform(draft)
    return draft
  })
}

export function readRuleDraft(item?: Proposal): RuleDraft {
  const payload = item?.payload ?? {}
  const text = (key: string) => {
    if (payload[key] == null) return ''
    if (typeof payload[key] !== 'string')
      throw new Error(`기존 ${key} 형식을 읽을 수 없습니다.`)
    return payload[key] as string
  }
  const conditions = item ? payload.conditions : [emptyCondition()]
  if (!Array.isArray(conditions) || !conditions.length)
    throw new Error('기존 비교 조건이 없어 수정을 중단했습니다.')
  return {
    name: text('name'),
    code: text('code'),
    description: text('description'),
    reason:
      typeof item?.evidence.reason === 'string' ? item.evidence.reason : '',
    linkTypeId: payload.linkTypeId == null ? '' : String(payload.linkTypeId),
    linkTypeCode: text('linkTypeCode'),
    conditions: conditions.map((condition) => {
      if (
        !isRecord(condition) ||
        (item &&
          (condition.operator !== 'EQ' ||
            typeof condition.sourceProperty !== 'string' ||
            typeof condition.targetProperty !== 'string'))
      )
        throw new Error(
          '기존 비교 조건 형식을 지원하지 않아 수정을 중단했습니다.',
        )
      return {
        original: item ? condition : {},
        sourceProperty: String(condition.sourceProperty ?? ''),
        targetProperty: String(condition.targetProperty ?? ''),
        sourceTransforms: readTransforms(condition.sourceTransforms),
        targetTransforms: readTransforms(condition.targetTransforms),
      }
    }),
  }
}

export function buildTransform(step: TransformDraft) {
  if (!Object.hasOwn(transformLabels, step.op))
    throw new Error('지원하는 변환 함수를 선택해 주세요.')
  let arg: unknown = null
  if (noArgs.includes(step.op)) {
    if (step.arg !== '')
      throw new Error('이 변환 함수에는 인자를 넣을 수 없습니다.')
  } else if (step.op === 'PAD_ZEROS') {
    arg = Number(step.arg)
    if (
      !/^\d+$/.test(step.arg) ||
      !Number.isSafeInteger(arg) ||
      Number(arg) < 1 ||
      Number(arg) > 2147483647
    )
      throw new Error('0 채우기 자릿수는 1 이상의 정수여야 합니다.')
  } else if (step.op === 'MAP_VALUE' || step.op === 'MAP_KNOWN') {
    try {
      arg = JSON.parse(step.arg)
    } catch {
      throw new Error('값 대응표는 JSON 객체로 입력해 주세요.')
    }
    if (
      !isRecord(arg) ||
      !Object.keys(arg).length ||
      Object.values(arg).some((value) => typeof value !== 'string')
    )
      throw new Error(
        '값 대응표의 키와 값은 문자열이어야 하며 한 쌍 이상 필요합니다.',
      )
  } else {
    if (!step.arg.length) throw new Error('접두어를 입력해 주세요.')
    arg = step.arg
  }
  return { op: step.op, arg }
}

export function buildRule(draft: RuleDraft, original?: Proposal) {
  const payload = {
    ...(original?.payload ?? {}),
    name: draft.name.trim(),
    code: draft.code.trim(),
    description: draft.description,
  }
  const relation: Record<string, unknown> = { ...payload }
  delete relation.linkTypeId
  delete relation.linkTypeCode
  if (draft.linkTypeId) {
    const id = Number(draft.linkTypeId)
    if (!/^\d+$/.test(draft.linkTypeId) || !Number.isSafeInteger(id) || id < 1)
      throw new Error('관계 유형 ID는 양의 정수로 입력해 주세요.')
    relation.linkTypeId = id
  } else if (!original?.analysisRunId || !draft.linkTypeCode.trim())
    throw new Error('기존 관계 유형 ID를 입력해 주세요.')
  if (draft.linkTypeCode.trim())
    relation.linkTypeCode = draft.linkTypeCode.trim()
  // AI 묶음 후보의 관계 유형(payload.linkType)은 같은 관계 유형을 가리킬 때만 남긴다.
  const embedded = relation.linkType
  if (
    relation.linkTypeId !== undefined ||
    typeof embedded !== 'object' ||
    embedded === null ||
    (embedded as Record<string, unknown>).code !== relation.linkTypeCode
  )
    delete relation.linkType
  if (!draft.conditions.length)
    throw new Error('비교 조건을 하나 이상 추가해 주세요.')
  relation.conditions = draft.conditions.map((condition, index) => {
    if (!condition.sourceProperty.trim() || !condition.targetProperty.trim())
      throw new Error(
        `조건 ${index + 1}의 출발·도착 속성 코드를 입력해 주세요.`,
      )
    return {
      ...condition.original,
      sourceProperty: condition.sourceProperty.trim(),
      targetProperty: condition.targetProperty.trim(),
      operator: 'EQ',
      sourceTransforms: condition.sourceTransforms.map(buildTransform),
      targetTransforms: condition.targetTransforms.map(buildTransform),
    }
  })
  const evidence = { ...(original?.evidence ?? {}) }
  if (draft.reason) evidence.reason = draft.reason
  else if (typeof evidence.reason === 'string') delete evidence.reason
  return { payload: relation, evidence }
}
