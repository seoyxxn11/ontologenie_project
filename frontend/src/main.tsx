import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
import './index.css'
import App from './App.tsx'
import { ApiError } from './api/errors'
import { prepareDevelopmentApi } from './developmentApi'
import { trackUiMotionInput } from './components/uiMotion'

const stopTrackingUiMotion = trackUiMotionInput()
if (import.meta.hot) import.meta.hot.dispose(stopTrackingUiMotion)

const queryClient = new QueryClient({
  defaultOptions: {
    queries: {
      retry: (count, error) =>
        !(
          error instanceof ApiError &&
          (error.status === 401 || error.status === 403)
        ) && count < 3,
    },
  },
})

prepareDevelopmentApi()
  .then(async (ready) => {
    if (!ready) return
    const preview =
      import.meta.env.DEV && new URLSearchParams(location.search).has('uiQa')
        ? await (await import('./mocks/uiPreview')).prepareUiPreview()
        : null
    createRoot(document.getElementById('root')!).render(
      <StrictMode>
        <QueryClientProvider client={queryClient}>
          <App />
          {preview}
        </QueryClientProvider>
      </StrictMode>,
    )
  })
  .catch(() => {
    document.getElementById('root')!.textContent =
      '화면을 시작하지 못했습니다. 개발 서버 설정을 확인한 뒤 새로고침해 주세요.'
  })
