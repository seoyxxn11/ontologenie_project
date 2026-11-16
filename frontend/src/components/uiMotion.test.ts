import { fireEvent } from '@testing-library/react'
import { afterEach, expect, test, vi } from 'vitest'
import { trackUiMotionInput } from './uiMotion'

let stopTracking: (() => void) | undefined
afterEach(() => {
  stopTracking?.()
  stopTracking = undefined
  document.body.replaceChildren()
  delete document.documentElement.dataset.uiInput
})

test('포인터·키보드 입력을 화면 열기 핸들러보다 먼저 반영한다', () => {
  stopTracking = trackUiMotionInput()
  const button = document.createElement('button')
  document.body.append(button)
  const inputAtOpen: (string | undefined)[] = []
  button.addEventListener('pointerdown', () => {
    inputAtOpen.push(document.documentElement.dataset.uiInput)
  })
  button.addEventListener('keydown', () => {
    inputAtOpen.push(document.documentElement.dataset.uiInput)
  })

  expect(document.documentElement.dataset.uiInput).toBe('initial')
  fireEvent.keyDown(button, { key: 'Tab' })
  fireEvent.pointerDown(button, { pointerType: 'mouse' })
  fireEvent.keyDown(button, { key: 'Enter' })
  fireEvent.pointerDown(button, { pointerType: 'touch' })
  expect(inputAtOpen).toEqual(['keyboard', 'pointer', 'keyboard', 'pointer'])
})

test('정리 후에는 입력을 추적하지 않고 이전 속성을 복원한다', () => {
  document.documentElement.dataset.uiInput = 'previous'
  stopTracking = trackUiMotionInput()
  const onKeyDown = vi.fn()
  document.addEventListener('keydown', onKeyDown, { once: true })
  stopTracking()
  stopTracking = undefined

  fireEvent.pointerDown(document.body)
  fireEvent.keyDown(document.body, { key: 'Enter' })
  expect(document.documentElement.dataset.uiInput).toBe('previous')
  expect(onKeyDown).toHaveBeenCalledOnce()
})
