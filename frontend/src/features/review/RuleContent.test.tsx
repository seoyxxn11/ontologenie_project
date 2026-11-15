import { render, screen, within } from '@testing-library/react'
import { expect, test } from 'vitest'
import { proposalFixtures } from '../../mocks/proposalHandlers'
import RuleContent from './RuleContent'

test('같음 조건과 출발·대상 변환을 각 값에 구분해 표시한다', () => {
  render(
    <RuleContent
      proposal={{
        ...proposalFixtures[0],
        payload: {
          conditions: [
            {
              sourceProperty: 'source.code',
              targetProperty: 'target.code',
              operator: 'EQ',
              sourceTransforms: [{ op: 'UPPER', arg: null }],
              targetTransforms: [{ op: 'TRIM', arg: null }],
            },
          ],
        },
      }}
    />,
  )
  expect(
    screen.getByText('아래 처리를 거친 두 값이 같으면 연결합니다.'),
  ).toBeInTheDocument()
  const source = screen.getByText('출발 속성').closest('dl')!
  const target = screen.getByText('대상 속성').closest('dl')!
  expect(within(source).getByText('대문자 변환')).toBeInTheDocument()
  expect(within(source).queryByText('앞뒤 공백 제거')).not.toBeInTheDocument()
  expect(within(target).getByText('앞뒤 공백 제거')).toBeInTheDocument()
})

test('알 수 없는 비교 연산을 같음으로 오인시키지 않는다', () => {
  render(
    <RuleContent
      proposal={{
        ...proposalFixtures[0],
        payload: {
          conditions: [
            {
              sourceProperty: 'a',
              targetProperty: 'b',
              operator: 'CUSTOM',
              sourceTransforms: [],
              targetTransforms: [],
            },
          ],
        },
      }}
    />,
  )
  expect(screen.getByText('비교 연산: CUSTOM')).toBeInTheDocument()
  expect(screen.queryByText('같음 (=)')).not.toBeInTheDocument()
  expect(screen.getAllByText('변환 없이 그대로 사용')).toHaveLength(2)
})
