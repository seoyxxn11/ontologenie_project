import { expect, test } from 'vitest'
import { proposalFixtures } from '../../mocks/proposalHandlers'
import { buildRule, buildTransform, readRuleDraft } from './ruleDraft'

test('빈 관계·속성·조건은 저장 요청 전에 거절한다', () => {
  const draft = readRuleDraft()
  expect(() => buildRule(draft)).toThrow('관계 유형 ID')
  draft.linkTypeId = '2'
  expect(() => buildRule(draft)).toThrow('출발·도착 속성')
  draft.conditions = []
  expect(() => buildRule(draft)).toThrow('하나 이상')
})

test.each([
  ['PAD_ZEROS', '0'],
  ['PAD_ZEROS', '2.5'],
  ['PAD_ZEROS', '2147483648'],
  ['MAP_VALUE', '{}'],
  ['MAP_VALUE', '{"a":1}'],
  ['MAP_VALUE', 'bad'],
  ['UPPER', 'arg'],
  ['ADD_PREFIX', ''],
  ['UNKNOWN', ''],
])('잘못된 변환 %s %s를 거절한다', (op, arg) => {
  expect(() => buildTransform({ op, arg })).toThrow()
})

test('열 가지 변환과 인자를 서버 계약으로 직렬화한다', () => {
  for (const op of [
    'UPPER',
    'TRIM',
    'EXTRACT_NUMBER',
    'TO_INTEGER',
    'TO_DECIMAL',
    'TO_DATE',
  ])
    expect(buildTransform({ op, arg: '' })).toEqual({ op, arg: null })
  for (const op of ['STRIP_PREFIX', 'ADD_PREFIX'])
    expect(buildTransform({ op, arg: 'PR-' })).toEqual({ op, arg: 'PR-' })
  expect(buildTransform({ op: 'PAD_ZEROS', arg: '4' })).toEqual({
    op: 'PAD_ZEROS',
    arg: 4,
  })
  expect(buildTransform({ op: 'MAP_VALUE', arg: '{"PR03":"PR-03"}' })).toEqual({
    op: 'MAP_VALUE',
    arg: { PR03: 'PR-03' },
  })
})

test('기존 추가 필드·근거·변환 순서를 보존한다', () => {
  const original = structuredClone(proposalFixtures[0])
  original.payload.sourceFilter = { status: 'ACTIVE' }
  original.evidence.originalText = '검사용 원문'
  const draft = readRuleDraft(original)
  draft.conditions[0].sourceTransforms.push({ op: 'PAD_ZEROS', arg: '4' })
  const result = buildRule(draft, original)
  expect(result.payload.sourceFilter).toEqual({ status: 'ACTIVE' })
  expect(result.evidence.originalText).toBe('검사용 원문')
  expect(result.payload.conditions).toMatchObject([
    {
      sourceTransforms: [
        { op: 'UPPER', arg: null },
        { op: 'PAD_ZEROS', arg: 4 },
      ],
    },
  ])
})

test('알 수 없는 기존 조건·변환은 삭제하거나 바꿔서 저장하지 않는다', () => {
  const original = structuredClone(proposalFixtures[0])
  original.payload.conditions = [
    { sourceProperty: 'code', targetProperty: 'code', operator: 'NE' },
  ]
  expect(() => readRuleDraft(original)).toThrow('기존 비교 조건')
  original.payload.conditions = [
    {
      sourceProperty: 'code',
      targetProperty: 'code',
      operator: 'EQ',
      sourceTransforms: [{ op: 'UNKNOWN' }],
    },
  ]
  expect(() => readRuleDraft(original)).toThrow('지원하지 않는 기존 변환')
})

test('같은 분석의 미확정 관계 유형은 코드로 유지한다', () => {
  const original = structuredClone(proposalFixtures[0])
  delete original.payload.linkTypeId
  original.payload.linkTypeCode = 'PENDING_RELATION'
  const result = buildRule(readRuleDraft(original), original)
  expect(result.payload.linkTypeCode).toBe('PENDING_RELATION')
  expect(result.payload).not.toHaveProperty('linkTypeId')
})

test('AI 묶음 후보의 관계 유형은 같은 관계를 가리킬 때만 남긴다', () => {
  const original = structuredClone(proposalFixtures[0])
  delete original.payload.linkTypeId
  original.payload.linkTypeCode = 'WO_TO_SO'
  original.payload.linkType = {
    code: 'WO_TO_SO',
    sourceTypeCode: 'WORK_ORDER',
    targetTypeCode: 'SALES_ORDER',
  }
  const kept = buildRule(readRuleDraft(original), original)
  expect(kept.payload.linkType).toEqual(original.payload.linkType)

  const recoded = readRuleDraft(original)
  recoded.linkTypeCode = 'EXISTING_RELATION'
  expect(buildRule(recoded, original).payload).not.toHaveProperty('linkType')

  const byId = readRuleDraft(original)
  byId.linkTypeId = '2'
  expect(buildRule(byId, original).payload).not.toHaveProperty('linkType')
})
