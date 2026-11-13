import { expect, test } from 'vitest'
import { createRefitCheck } from './resizeRefit'

test('창을 조금씩 끌어 크게 바꾸면 마지막으로 맞춘 크기에서 기준만큼 바뀔 때 다시 맞춘다', () => {
  const shouldRefit = createRefitCheck({ width: 800, height: 500 }, 40)

  expect(shouldRefit({ width: 810, height: 500 })).toBe(false)
  expect(shouldRefit({ width: 820, height: 500 })).toBe(false)
  expect(shouldRefit({ width: 830, height: 500 })).toBe(false)
  expect(shouldRefit({ width: 840, height: 500 })).toBe(true)
  // 다시 맞춘 크기(840)가 새 기준이 된다.
  expect(shouldRefit({ width: 850, height: 500 })).toBe(false)
})

test('스크롤바가 생기고 없어지는 정도의 변화로는 다시 맞추지 않는다', () => {
  const shouldRefit = createRefitCheck({ width: 800, height: 500 }, 40)

  expect(shouldRefit({ width: 785, height: 500 })).toBe(false)
  expect(shouldRefit({ width: 800, height: 500 })).toBe(false)
  expect(shouldRefit({ width: 785, height: 500 })).toBe(false)
})

test('높이가 크게 바뀌어도 다시 맞춘다', () => {
  const shouldRefit = createRefitCheck({ width: 800, height: 500 }, 40)

  expect(shouldRefit({ width: 800, height: 640 })).toBe(true)
})
