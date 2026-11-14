import { afterEach, expect, test, vi } from 'vitest'
import { prepareDevelopmentApi } from './developmentApi'

const { start } = vi.hoisted(() => ({ start: vi.fn(async () => undefined) }))
vi.mock('./mocks/browser', () => ({ worker: { start } }))

afterEach(() => {
  vi.unstubAllEnvs()
  vi.unstubAllGlobals()
  start.mockClear()
})

function serviceWorker(controller: string | null = null) {
  const unregister = vi.fn(async () => true)
  const unrelated = {
    active: { scriptURL: 'http://localhost/other-worker.js' },
    unregister: vi.fn(),
  }
  const mock = {
    active: {
      scriptURL: new URL('/mockServiceWorker.js', location.origin).href,
    },
    unregister,
  }
  const container = {
    getRegistrations: vi.fn(async () => [mock, unrelated]),
    controller: controller ? { scriptURL: controller } : null,
  }
  vi.stubGlobal('navigator', { serviceWorker: container })
  return { container, unregister, unrelated }
}

function realMode() {
  vi.stubEnv('DEV', true)
  vi.stubEnv('VITE_API_MODE', 'real')
}

test('mock 모드는 워커 준비 후 시작하며 없는 API만 차단하고 정적 파일은 통과시킨다', async () => {
  vi.stubEnv('DEV', true)
  vi.stubEnv('VITE_API_MODE', 'mock')
  expect(await prepareDevelopmentApi()).toBe(true)
  expect(start).toHaveBeenCalledOnce()
  const options = vi.mocked(start).mock.calls[0] as unknown as [
    { onUnhandledFrame: (args: unknown) => void },
  ]
  const error = vi.fn()
  for (const path of [
    '/src/App.tsx',
    '/fonts/example.woff2',
    '/api/v1/missing',
  ]) {
    options[0].onUnhandledFrame({
      frame: {
        protocol: 'http',
        data: { request: new Request(`http://localhost${path}`) },
      },
      defaults: { error },
    })
  }
  expect(error).toHaveBeenCalledOnce()
})

test('real 모드는 모의 워커만 해제하며 모의 로그인 모듈을 시작하지 않는다', async () => {
  realMode()
  const { unregister, unrelated } = serviceWorker()
  expect(await prepareDevelopmentApi()).toBe(true)
  expect(unregister).toHaveBeenCalledOnce()
  expect(unrelated.unregister).not.toHaveBeenCalled()
  expect(start).not.toHaveBeenCalled()
})

test('현재 문서를 모의 워커가 제어하면 해제 후 새로고침하고 앱 시작은 보류한다', async () => {
  realMode()
  serviceWorker(new URL('/mockServiceWorker.js', location.origin).href)
  const reload = vi.fn()
  expect(await prepareDevelopmentApi(reload)).toBe(false)
  expect(reload).toHaveBeenCalledOnce()
})

test('워커 해제가 실패하면 실제 API로 전환됐다고 시작하지 않는다', async () => {
  realMode()
  const { unregister } = serviceWorker()
  unregister.mockResolvedValue(false)
  await expect(prepareDevelopmentApi()).rejects.toThrow('해제하지 못했습니다')
  expect(start).not.toHaveBeenCalled()
})

test('운영 빌드는 mock 설정이 남아도 워커를 시작하거나 브라우저 워커를 변경하지 않는다', async () => {
  vi.stubEnv('DEV', false)
  vi.stubEnv('VITE_API_MODE', 'mock')
  const { container } = serviceWorker()
  expect(await prepareDevelopmentApi()).toBe(true)
  expect(container.getRegistrations).not.toHaveBeenCalled()
  expect(start).not.toHaveBeenCalled()
})
