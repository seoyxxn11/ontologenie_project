import { createElement } from 'react'
import { resolveApiMode } from '../apiMode'
import { setMockAccount } from './auth'
import { worker } from './browser'
import UiPreviewTools from './UiPreviewTools'
import {
  uiPreviewAccount,
  uiPreviewCase,
  uiPreviewHandlers,
} from './uiPreviewData'

export async function prepareUiPreview() {
  if (
    !import.meta.env.DEV ||
    resolveApiMode(
      true,
      import.meta.env.VITE_API_MODE,
      import.meta.env.MODE,
    ) !== 'mock'
  )
    throw new Error(
      'UI 비교 화면은 개발 서버의 모의 API 모드에서만 열 수 있습니다.',
    )
  const params = new URLSearchParams(location.search)
  const selected = uiPreviewCase(params.get('uiQa'))
  document.documentElement.style.fontSize =
    params.get('qaText') === 'large' ? '200%' : ''
  setMockAccount(selected === 'login' ? null : uiPreviewAccount(selected))
  worker.use(...uiPreviewHandlers(selected))
  document.documentElement.dataset.uiMotionPreview =
    params.get('qaMotion') === 'on' ? 'on' : 'off'
  document.documentElement.dataset.uiMotionSlow =
    params.get('qaSlow') === 'on' ? 'on' : 'off'
  return createElement(UiPreviewTools, { selected })
}
