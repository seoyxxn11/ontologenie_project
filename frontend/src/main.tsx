import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
import './index.css'
import App from './App.tsx'
import { ApiError } from './api/errors'

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

async function enableMocking() {
  if (!import.meta.env.DEV) {
    return
  }

  const { worker } = await import('./mocks/browser')
  await worker.start({ onUnhandledFrame: 'bypass', quiet: true })
}

enableMocking().then(() => {
  createRoot(document.getElementById('root')!).render(
    <StrictMode>
      <QueryClientProvider client={queryClient}>
        <App />
      </QueryClientProvider>
    </StrictMode>,
  )
})
