import { useState } from 'react'
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
import AgentPage from './features/agent/AgentPage'
import AuditPage from './features/audit/AuditPage'
import ExplorerPage from './features/explorer/ExplorerPage'
import HistoryPage from './features/history/HistoryPage'
import AuthProvider from './features/auth/AuthProvider'
import RequireAuth from './features/auth/RequireAuth'
import LoginPage from './features/auth/LoginPage'
import PasswordChangePage from './features/auth/PasswordChangePage'
import SourcesPage from './features/sources/SourcesPage'
import RequireAccess from './features/auth/RequireAccess'
import ProposalsPage from './features/review/ProposalsPage'
import ProposalDetail from './features/review/ProposalDetail'
import ProposalHistoryPage from './features/review/ProposalHistoryPage'
import RuleEditorPage from './features/review/RuleEditorPage'
import AccountsPage from './features/accounts/AccountsPage'
import AccountEditorPage from './features/accounts/AccountEditorPage'
import ActionDefinitionsPage from './features/actions/ActionDefinitionsPage'
import ActionDefinitionEditorPage from './features/actions/ActionDefinitionEditorPage'
import ObjectTypesPage from './features/ontology/ObjectTypesPage'
import CheckRunsPage from './features/checks/CheckRunsPage'

function App() {
  const [router] = useState(() =>
    createBrowserRouter(
      createRoutesFromElements(
        <Route
          element={
            <AuthProvider>
              <Outlet />
            </AuthProvider>
          }
        >
          <Route path="/login" element={<LoginPage />} />

          <Route element={<RequireAuth />}>
            <Route path="/change-password" element={<PasswordChangePage />} />
            <Route element={<AppLayout />}>
              <Route element={<RequireAccess access="workspace" />}>
                <Route path="/" element={<Navigate to="/explorer" replace />} />
                <Route path="/explorer" element={<ExplorerPage />} />
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
