import { expect, test } from 'vitest'
import {
  formatCount,
  formatObjectKey,
  formatPropertyValue,
  withAndParticle,
} from './format'

test('건수는 천 단위 쉼표로 표시한다', () => {
  expect(formatCount(0)).toBe('0')
  expect(formatCount(1284)).toBe('1,284')
  expect(formatCount(5064)).toBe('5,064')
})

test('받침이 있으면 과를 붙인다', () => {
  expect(withAndParticle('프레스 작업')).toBe('프레스 작업과')
})

test('받침이 없으면 와를 붙인다', () => {
  expect(withAndParticle('프레스 3호기')).toBe('프레스 3호기와')
})

test('한글로 끝나지 않으면 와(과)를 붙인다', () => {
  expect(withAndParticle('PR-03')).toBe('PR-03와(과)')
})

test('객체와 배열 값은 JSON 문자열로 보여 준다', () => {
  expect(formatPropertyValue({ basis: 'skill', score: 3 })).toBe(
    '{"basis":"skill","score":3}',
  )
  expect(formatPropertyValue([1, 'a'])).toBe('[1,"a"]')
})

test('값이 없으면 -, 단순 값은 그대로 보여 준다', () => {
  expect(formatPropertyValue(null)).toBe('-')
  expect(formatPropertyValue(undefined)).toBe('-')
  expect(formatPropertyValue(12.5)).toBe('12.5')
  expect(formatPropertyValue(false)).toBe('false')
})

test('원천 키 값을 표시 이름 기본값과 같은 모양으로 잇는다', () => {
  expect(
    formatObjectKey({
      id: 7,
      displayName: '원주 창고 품목',
      sourceKey: { warehouse: 'W1', item_code: 'P101' },
    }),
  ).toBe('W1 / P101')
  expect(
    formatObjectKey({
      id: 8,
      displayName: '1라인 1호 프레스',
      sourceKey: { code: 101 },
    }),
  ).toBe('101')
})

test('표시 이름이 원천 키와 같으면 키를 다시 보여 주지 않는다', () => {
  expect(
    formatObjectKey({
      id: 7,
      displayName: 'W1 / P101',
      sourceKey: { warehouse: 'W1', item_code: 'P101' },
    }),
  ).toBeNull()
})

test('jsonb가 칼럼 순서를 바꿔 보내도 표시 이름과 같은 키를 다시 보여 주지 않는다', () => {
  // 표시 이름은 매핑의 키 칼럼 순서(warehouse, item_code), API는 jsonb 순서(짧은 키 먼저)다
  const sourceKey = JSON.parse(
    '{"item_code":"P101","warehouse":"W1"}',
  ) as Record<string, unknown>
  expect(
    formatObjectKey({ id: 7, displayName: 'W1 / P101', sourceKey }),
  ).toBeNull()
  expect(
    formatObjectKey({ id: 7, displayName: '원주 창고 품목', sourceKey }),
  ).toBe('P101 / W1')
})

test('권한으로 가린 원천 키 값은 null 글자로 보여 주지 않는다', () => {
  expect(
    formatObjectKey({ id: 9, displayName: '#9', sourceKey: { wo_no: null } }),
  ).toBeNull()
  expect(
    formatObjectKey({
      id: 9,
      displayName: '1라인 1호 프레스',
      sourceKey: { wo_no: null },
    }),
  ).toBe('#9')
  expect(
    formatObjectKey({
      id: 9,
      displayName: 'P101',
      sourceKey: { item_code: 'P101', warehouse: null },
    }),
  ).toBeNull()
  expect(
    formatObjectKey({
      id: 9,
      displayName: '원주 창고 품목',
      sourceKey: { item_code: 'P101', warehouse: null },
    }),
  ).toBe('P101')
})

test('원천 키가 없는 객체는 내부 번호를 보여 준다', () => {
  expect(formatObjectKey({ id: 7, displayName: '수동 객체' })).toBe('#7')
  expect(
    formatObjectKey({ id: 7, displayName: '수동 객체', sourceKey: {} }),
  ).toBe('#7')
})
