import { BrowserRouter, Navigate, Route, Routes } from 'react-router'
import AppLayout from './components/AppLayout'
import NotFoundPage from './components/NotFoundPage'
import PagePlaceholder from './components/PagePlaceholder'
import AgentPage from './features/agent/AgentPage'
import AuditPage from './features/audit/AuditPage'
import ExplorerPage from './features/explorer/ExplorerPage'
import HistoryPage from './features/history/HistoryPage'
import AuthProvider from './features/auth/AuthProvider'
import RequireAuth from './features/auth/RequireAuth'
import LoginPage from './features/auth/LoginPage'
import PasswordChangePage from './features/auth/PasswordChangePage'

function App() {
  return (
    <BrowserRouter>
      <AuthProvider>
        <Routes>
          <Route path="/login" element={<LoginPage />} />

          <Route element={<RequireAuth />}>
            <Route path="/change-password" element={<PasswordChangePage />} />
            <Route element={<AppLayout />}>
              <Route path="/" element={<Navigate to="/explorer" replace />} />
              <Route path="/explorer" element={<ExplorerPage />} />
              <Route path="/agent" element={<AgentPage />} />
              <Route path="/audit" element={<AuditPage />} />
              <Route path="/admin/history" element={<HistoryPage />} />
              <Route
                path="/admin/sources"
                element={<PagePlaceholder title="데이터 연결" />}
              />
              <Route
                path="/admin/rules"
                element={<PagePlaceholder title="연결 규칙" />}
              />
              <Route
                path="/admin/accounts"
                element={<PagePlaceholder title="계정·권한" />}
              />
              <Route path="*" element={<NotFoundPage />} />
            </Route>
          </Route>
        </Routes>
      </AuthProvider>
    </BrowserRouter>
  )
}

export default App
