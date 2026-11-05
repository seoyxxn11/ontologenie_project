import { BrowserRouter, Navigate, Route, Routes } from 'react-router'
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

function App() {
  return (
    <BrowserRouter>
      <AuthProvider>
        <Routes>
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
                <Route path="rules" element={<ProposalsPage />} />
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
                <Route path="*" element={<NotFoundPage />} />
              </Route>
            </Route>
          </Route>
        </Routes>
      </AuthProvider>
    </BrowserRouter>
  )
}

export default App
