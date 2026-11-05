import { apiGet } from '../../api/client'
import type { Proposal, ProposalOption } from '../review/types'

// 복수 후보는 선택지(옵션)로 온다. 현재 개정의 선택지만 본다.
export async function fetchDocumentLinkOptions(
  proposal: Proposal,
  signal?: AbortSignal,
) {
  return (
    await apiGet<ProposalOption[]>(
      `/proposals/${proposal.id}/options?revision=${proposal.revision}`,
      { signal },
    )
  ).data
}

// DOCUMENT_LINK 후보의 payload·evidence (ai/docs/api-internal.md 2절)
export type Assertion = 'AFFIRMED' | 'NEGATED' | 'UNCERTAIN'

export type Observation = {
  text: string
  spanStart: number
  spanEnd: number
  assertion: Assertion
}

// 글 속 시각 표현. normalized는 원천에서 기준 시각을 알 때만 온다.
export type TimeExpression = {
  text: string
  spanStart: number
  spanEnd: number
  normalized?: string
  basis?: string
}

export type DocumentLink = {
  documentId?: string
  mentionText?: string
  spanStart?: number
  spanEnd?: number
  targetObjectRef?: string
  expressionKind?: 'OBSERVED' | 'ESTIMATED'
  originalText?: string
  observations: Observation[]
  times: TimeExpression[]
  holdRecommended: boolean
  holdReason?: string
  reason?: string
}

export const assertionLabels: Record<
  Assertion,
  { label: string; className: string }
> = {
  AFFIRMED: { label: '확인', className: 'bg-emerald-100 text-emerald-900' },
  NEGATED: {
    label: '부정',
    className: 'bg-slate-200 text-slate-700 line-through',
  },
  UNCERTAIN: { label: '추정', className: 'bg-amber-100 text-amber-900' },
}

// 원문 표시 색. 관찰 표현은 확인·부정·추정, 그 밖에 연결한 언급과 시각
export const markLabels: Record<
  NonNullable<Segment['mark']>,
  { label: string; className: string }
> = {
  ...assertionLabels,
  mention: {
    label: '연결한 언급',
    className: 'bg-violet-100 font-semibold text-violet-900',
  },
  time: { label: '시각', className: 'bg-sky-100 text-sky-900' },
}

export const expressionLabels = {
  OBSERVED: '확인된 언급',
  ESTIMATED: '추정 표현',
} as const

function text(value: unknown) {
  return typeof value === 'string' && value ? value : undefined
}

function offset(value: unknown) {
  return typeof value === 'number' && Number.isInteger(value) && value >= 0
    ? value
    : undefined
}

function observation(value: unknown): Observation | undefined {
  if (typeof value !== 'object' || value === null) return undefined
  const item = value as Record<string, unknown>
  const start = offset(item.spanStart)
  const end = offset(item.spanEnd)
  const assertion = item.assertion
  if (
    start === undefined ||
    end === undefined ||
    end <= start ||
    !(
      assertion === 'AFFIRMED' ||
      assertion === 'NEGATED' ||
      assertion === 'UNCERTAIN'
    )
  )
    return undefined
  return {
    text: text(item.text) ?? '',
    spanStart: start,
    spanEnd: end,
    assertion,
  }
}

function timeExpression(value: unknown): TimeExpression | undefined {
  if (typeof value !== 'object' || value === null) return undefined
  const item = value as Record<string, unknown>
  const start = offset(item.spanStart)
  const end = offset(item.spanEnd)
  const label = text(item.text)
  if (start === undefined || end === undefined || end <= start || !label)
    return undefined
  return {
    text: label,
    spanStart: start,
    spanEnd: end,
    normalized: text(item.normalized),
    basis: text(item.basis),
  }
}

// 서버가 주는 JSON을 화면에서 쓸 모양으로 고른다. 모르는 값은 버린다.
export function readDocumentLink(proposal: Proposal): DocumentLink {
  const { payload, evidence } = proposal
  const expression = payload.expressionKind
  return {
    documentId: text(payload.documentId),
    mentionText: text(payload.mentionText),
    spanStart: offset(payload.spanStart),
    spanEnd: offset(payload.spanEnd),
    targetObjectRef: text(payload.targetObjectRef),
    expressionKind:
      expression === 'OBSERVED' || expression === 'ESTIMATED'
        ? expression
        : undefined,
    originalText: text(evidence.originalText),
    observations: Array.isArray(evidence.observations)
      ? evidence.observations.flatMap((item) => observation(item) ?? [])
      : [],
    times: Array.isArray(evidence.times)
      ? evidence.times.flatMap((item) => timeExpression(item) ?? [])
      : [],
    holdRecommended: evidence.holdRecommended === true,
    holdReason: text(evidence.holdReason),
    reason: text(evidence.reason),
  }
}

export type Segment = { text: string; mark?: 'mention' | 'time' | Assertion }

// 원문을 언급·관찰 표현·시각 위치로 나눈다. 겹치는 구간은 먼저 나온 것만 표시한다.
export function splitOriginal(link: DocumentLink): Segment[] {
  const source = link.originalText ?? ''
  const marks: { start: number; end: number; mark: Segment['mark'] }[] = []
  if (
    link.spanStart !== undefined &&
    link.spanEnd !== undefined &&
    link.spanEnd <= source.length
  )
    marks.push({ start: link.spanStart, end: link.spanEnd, mark: 'mention' })
  for (const item of link.observations)
    if (item.spanEnd <= source.length)
      marks.push({
        start: item.spanStart,
        end: item.spanEnd,
        mark: item.assertion,
      })
  for (const item of link.times)
    if (item.spanEnd <= source.length)
      marks.push({ start: item.spanStart, end: item.spanEnd, mark: 'time' })
  marks.sort((a, b) => a.start - b.start)
  const segments: Segment[] = []
  let cursor = 0
  for (const item of marks) {
    if (item.start < cursor) continue
    if (item.start > cursor)
      segments.push({ text: source.slice(cursor, item.start) })
    segments.push({ text: source.slice(item.start, item.end), mark: item.mark })
    cursor = item.end
  }
  if (cursor < source.length) segments.push({ text: source.slice(cursor) })
  return segments
}
