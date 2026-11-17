import { expect, test } from 'vitest'
import { summarizeExecutionValues, targetIdKey } from './executionSummary'

const source = { targetType: 'data_source', targetId: 1 }

test('내부 값·대상 번호·입력과 같은 결과를 빼고 바뀐 내용만 남긴다', () => {
  expect(
    summarizeExecutionValues(
      source,
      {
        rowVersion: 6,
        syncEnabled: false,
        dataSourceId: 1,
        syncIntervalMinutes: null,
      },
      { dataSourceId: 1 },
    ),
  ).toEqual({
    input: { syncEnabled: false, syncIntervalMinutes: null },
    result: {},
  })
})

test('대상 번호와 숫자만 같은 다른 항목과 다른 대상의 번호는 남긴다', () => {
  expect(
    summarizeExecutionValues(
      source,
      { accountId: 1, dataSourceId: 2 },
      { checkRunId: 1 },
    ),
  ).toEqual({
    input: { accountId: 1, dataSourceId: 2 },
    result: { checkRunId: 1 },
  })
})

test('결과와 입력의 이름이 같아도 값이 다르면 결과에 남긴다', () => {
  expect(
    summarizeExecutionValues(
      { targetType: null, targetId: null },
      { status: 'QUEUED', items: [1, 2] },
      { status: 'CREATED', items: [1, 2] },
    ),
  ).toEqual({
    input: { status: 'QUEUED', items: [1, 2] },
    result: { status: 'CREATED' },
  })
})

test('입력이나 결과가 없으면 빈 값으로 돌려준다', () => {
  expect(summarizeExecutionValues(source, null, null)).toEqual({
    input: {},
    result: {},
  })
})

test('모르는 대상 유형과 객체 기본 속성 이름은 대상 번호 항목으로 보지 않는다', () => {
  expect(targetIdKey('sync_run')).toBeUndefined()
  expect(targetIdKey('constructor')).toBeUndefined()
  expect(targetIdKey(null)).toBeUndefined()
  expect(targetIdKey('account')).toBe('accountId')
})
