import { lazy, Suspense, useState } from 'react'
import {
  createBrowserRouter,
  createRoutesFromElements,
  Navigate,
  Outlet,
  Route,
  RouterProvider,
} from 'react-router'
import AppLayout from './components/AppLayout'
import NotFoundPage from './components/NotFoundPage'
import RouteErrorPage from './components/RouteErrorPage'
import { LoadingState } from './components/Feedback'
import AuthProvider from './features/auth/AuthProvider'
import RequireAuth from './features/auth/RequireAuth'
import LoginPage from './features/auth/LoginPage'
import PasswordChangePage from './features/auth/PasswordChangePage'
import RequireAccess from './features/auth/RequireAccess'
import WorkDataLayout from './features/explorer/WorkDataLayout'

const AgentPage = lazy(() => import('./features/agent/AgentPage'))
const AuditPage = lazy(() => import('./features/audit/AuditPage'))
const ExplorerPage = lazy(() => import('./features/explorer/ExplorerPage'))
const ReassignmentPlansPage = lazy(
  () => import('./features/reassignment/ReassignmentPlansPage'),
)
const HistoryPage = lazy(() => import('./features/history/HistoryPage'))
const SourcesPage = lazy(() => import('./features/sources/SourcesPage'))
const ProposalsPage = lazy(() => import('./features/review/ProposalsPage'))
const ProposalDetail = lazy(() => import('./features/review/ProposalDetail'))
const ProposalHistoryPage = lazy(
  () => import('./features/review/ProposalHistoryPage'),
)
const RuleEditorPage = lazy(() => import('./features/review/RuleEditorPage'))
const AccountsPage = lazy(() => import('./features/accounts/AccountsPage'))
const AccountEditorPage = lazy(
  () => import('./features/accounts/AccountEditorPage'),
)
const ActionDefinitionsPage = lazy(
  () => import('./features/actions/ActionDefinitionsPage'),
)
const ActionDefinitionEditorPage = lazy(
  () => import('./features/actions/ActionDefinitionEditorPage'),
)
const ObjectTypesPage = lazy(
  () => import('./features/ontology/ObjectTypesPage'),
)
const CheckRunsPage = lazy(() => import('./features/checks/CheckRunsPage'))

function App() {
  const [router] = useState(() =>
    createBrowserRouter(
      createRoutesFromElements(
        <Route
          errorElement={<RouteErrorPage />}
          element={
            <AuthProvider>
              <Suspense
                fallback={<LoadingState message="화면을 불러오는 중…" />}
              >
                <Outlet />
              </Suspense>
            </AuthProvider>
          }
        >
          <Route path="/login" element={<LoginPage />} />

          <Route element={<RequireAuth />}>
            <Route path="/change-password" element={<PasswordChangePage />} />
            <Route element={<AppLayout />}>
              <Route element={<RequireAccess access="workspace" />}>
                <Route path="/" element={<Navigate to="/explorer" replace />} />
                <Route path="/explorer" element={<WorkDataLayout />}>
                  <Route index element={<ExplorerPage />} />
                  <Route path="plans" element={<ReassignmentPlansPage />} />
                </Route>
                <Route path="/agent" element={<AgentPage />} />
                <Route path="/audit" element={<AuditPage />} />
                <Route path="*" element={<NotFoundPage />} />
              </Route>
              <Route
                path="/admin"
                element={<RequireAccess access="administration" />}
              >
                <Route
                  index
                  element={<Navigate to="/admin/sources" replace />}
                />
                <Route path="history" element={<HistoryPage />} />
                <Route path="sources" element={<SourcesPage />} />
                <Route path="object-types" element={<ObjectTypesPage />} />
                <Route path="rules" element={<ProposalsPage />} />
                <Route path="checks" element={<CheckRunsPage />} />
                <Route path="rules/new" element={<RuleEditorPage />} />
                <Route path="rules/:id/edit" element={<RuleEditorPage />} />
                <Route
                  path="rules/:id/history"
                  element={<ProposalHistoryPage />}
                />
                <Route path="rules/:id" element={<ProposalDetail />} />
                <Route path="accounts" element={<AccountsPage />} />
                <Route path="accounts/new" element={<AccountEditorPage />} />
                <Route path="accounts/:id" element={<AccountEditorPage />} />
                <Route path="actions" element={<ActionDefinitionsPage />} />
                <Route
                  path="actions/new"
                  caseSensitive
                  element={<ActionDefinitionEditorPage />}
                />
                <Route
                  path="actions/:code"
                  element={<ActionDefinitionEditorPage />}
                />
                <Route path="*" element={<NotFoundPage />} />
              </Route>
            </Route>
          </Route>
        </Route>,
      ),
    ),
  )
  return <RouterProvider router={router} />
}

export default App
