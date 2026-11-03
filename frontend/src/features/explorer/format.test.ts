import { expect, test } from 'vitest'
import { withAndParticle } from './format'

test('받침이 있으면 과를 붙인다', () => {
  expect(withAndParticle('프레스 작업')).toBe('프레스 작업과')
})

test('받침이 없으면 와를 붙인다', () => {
  expect(withAndParticle('프레스 3호기')).toBe('프레스 3호기와')
})

test('한글로 끝나지 않으면 와(과)를 붙인다', () => {
  expect(withAndParticle('PR-03')).toBe('PR-03와(과)')
})
