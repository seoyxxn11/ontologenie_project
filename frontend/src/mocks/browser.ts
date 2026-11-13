import { setupWorker } from 'msw/browser'
import { handlers } from './handlers'
import { restoreBrowserMockSession } from './auth'

if (import.meta.env.DEV) restoreBrowserMockSession()

export const worker = setupWorker(...handlers)
