import { useEffect, useRef, useState } from 'react'
import { Link, NavLink, Outlet, useLocation } from 'react-router'
import AccountMenu from '../features/auth/AccountMenu'
import { useAuth } from '../features/auth/auth-context'
import { canAccess } from '../features/auth/access'

const userMenus = [
  { to: '/explorer', label: '업무 데이터' },
  { to: '/agent', label: 'AI 업무 도우미' },
]

const adminMenus = [
  { to: '/admin/sources', label: '데이터 연결' },
  { to: '/admin/rules', label: '연결 규칙' },
  { to: '/admin/actions', label: 'Action 정의' },
]

const headerLinks = [
  { to: '/audit', label: '실행 이력' },
  { to: '/admin/object-types', label: '객체 유형' },
  { to: '/admin/checks', label: '정합성 검사' },
  { to: '/admin/history', label: '정의·규칙 이력' },
  { to: '/admin/accounts', label: '계정·권한' },
]

function menuClassName({ isActive }: { isActive: boolean }) {
  return `app-nav-link${isActive ? ' is-active' : ''}`
}

function headerLinkClassName({ isActive }: { isActive: boolean }) {
  return `app-header-link${isActive ? ' is-active' : ''}`
}

function AppLayout() {
  const rulesWorkspace = useLocation().pathname.startsWith('/admin/rules')
  const [panelOpen, setPanelOpen] = useState(false)
  const [compact, setCompact] = useState(
    () => window.matchMedia?.('(max-width: 900px)').matches ?? false,
  )
  const toggleRef = useRef<HTMLButtonElement>(null)
  const panelRef = useRef<HTMLElement>(null)
  const { account } = useAuth()
  const workspaceAllowed = canAccess(account, 'workspace')
  const administrationAllowed = canAccess(account, 'administration')
  const panelTitle = administrationAllowed ? '운영·관리' : '내 메뉴'

  useEffect(() => {
    const media = window.matchMedia?.('(max-width: 900px)')
    if (!media) return
    const update = () => setCompact(media.matches)
    media.addEventListener('change', update)
    return () => media.removeEventListener('change', update)
  }, [])

  function closePanel() {
    setPanelOpen(false)
    toggleRef.current?.focus()
  }

  useEffect(() => {
    if (!panelOpen) return
    const panel = panelRef.current
    const toggle = toggleRef.current
    const previousOverflow = document.body.style.overflow
    if (compact) {
      document.body.style.overflow = 'hidden'
      panel?.focus()
    }
    function handleKeyDown(event: KeyboardEvent) {
      if (event.defaultPrevented) return
      if (event.key === 'Escape') {
        setPanelOpen(false)
        toggle?.focus()
      }
      if (!compact || event.key !== 'Tab') return
      const elements = Array.from(
        panel?.querySelectorAll<HTMLElement>(
          'a[href], button:not(:disabled), [tabindex="0"]',
        ) ?? [],
      )
      const first = elements[0]
      const last = elements[elements.length - 1]
      if (
        event.shiftKey &&
        (document.activeElement === first || document.activeElement === panel)
      ) {
        event.preventDefault()
        last?.focus()
      } else if (
        !event.shiftKey &&
        (document.activeElement === last || document.activeElement === panel)
      ) {
        event.preventDefault()
        first?.focus()
      }
    }
    document.addEventListener('keydown', handleKeyDown)
    return () => {
      document.removeEventListener('keydown', handleKeyDown)
      if (compact) {
        document.body.style.overflow = previousOverflow
        toggle?.focus()
      }
    }
  }, [panelOpen, compact])

  const mainNavigation = (
    <nav aria-label="주 메뉴" className="main-nav">
      {[
        ...(workspaceAllowed ? userMenus : []),
        ...(administrationAllowed ? adminMenus : []),
      ].map((menu) => (
        <NavLink
          key={menu.to}
          to={menu.to}
          className={menuClassName}
          onClick={compact ? closePanel : undefined}
        >
          {menu.label}
        </NavLink>
      ))}
    </nav>
  )

  return (
    <div className={`app-shell${rulesWorkspace ? ' rules-shell' : ''}`}>
      <a className="app-skip-link" href="#workspace-main">
        본문으로 이동
      </a>
      <div className="app-frame">
        <header className="app-header" inert={compact && panelOpen}>
          <Link
            to="/explorer"
            className="app-brand font-semibold tracking-tight text-slate-900"
            aria-label="OntoloGenie"
          >
            <img
              src="/ontologenie-logo.svg"
              alt="OntoloGenie"
              width="250"
              height="32"
            />
          </Link>
          {!compact && mainNavigation}
          <button
            ref={toggleRef}
            type="button"
            className="app-panel-toggle"
            aria-label={panelOpen ? '메뉴 패널 접기' : '메뉴 패널 열기'}
            aria-controls="app-navigation-panel"
            aria-expanded={panelOpen}
            title={panelTitle}
            onClick={() => setPanelOpen((open) => !open)}
          >
            <svg
              viewBox="0 0 24 24"
              width="20"
              height="20"
              fill="none"
              stroke="currentColor"
              strokeWidth="1.8"
              strokeLinecap="round"
              strokeLinejoin="round"
              aria-hidden="true"
            >
              <path d="M10.19 5.24 L10.61 2.10 L13.39 2.10 L13.81 5.24 L15.50 5.94 L18.02 4.01 L19.99 5.98 L18.06 8.50 L18.76 10.19 L21.90 10.61 L21.90 13.39 L18.76 13.81 L18.06 15.50 L19.99 18.02 L18.02 19.99 L15.50 18.06 L13.81 18.76 L13.39 21.90 L10.61 21.90 L10.19 18.76 L8.50 18.06 L5.98 19.99 L4.01 18.02 L5.94 15.50 L5.24 13.81 L2.10 13.39 L2.10 10.61 L5.24 10.19 L5.94 8.50 L4.01 5.98 L5.98 4.01 L8.50 5.94 Z" />
              <circle cx="12" cy="12" r="3" />
            </svg>
          </button>
        </header>

        {compact && panelOpen && (
          <div
            className="app-panel-backdrop"
            aria-hidden="true"
            onClick={closePanel}
          />
        )}
        <div className="app-body">
          <aside
            id="app-navigation-panel"
            ref={panelRef}
            className="app-navigation-panel"
            hidden={!panelOpen}
            tabIndex={-1}
            role={compact ? 'dialog' : undefined}
            aria-modal={compact && panelOpen ? true : undefined}
            aria-labelledby="app-panel-title"
          >
            <div className="app-panel-heading">
              <h2 id="app-panel-title">{panelTitle}</h2>
              <button
                type="button"
                className="app-panel-toggle"
                aria-label="메뉴 패널 닫기"
                onClick={closePanel}
              >
                <svg
                  viewBox="0 0 24 24"
                  width="20"
                  height="20"
                  fill="none"
                  stroke="currentColor"
                  strokeWidth="1.8"
                  strokeLinecap="round"
                  aria-hidden="true"
                >
                  <path d="m6 6 12 12M18 6 6 18" />
                </svg>
              </button>
            </div>
            {compact && mainNavigation}
            <nav aria-label="계정 메뉴" className="app-panel-links">
              {headerLinks
                .filter((link) =>
                  link.to.startsWith('/admin/')
                    ? administrationAllowed
                    : workspaceAllowed,
                )
                .map((link) => (
                  <NavLink
                    key={link.to}
                    to={link.to}
                    className={headerLinkClassName}
                    onClick={compact ? closePanel : undefined}
                  >
                    {link.label}
                  </NavLink>
                ))}
            </nav>
            <div className="app-panel-account">
              <AccountMenu />
            </div>
          </aside>
          <main
            id="workspace-main"
            tabIndex={-1}
            className="workspace-main"
            inert={compact && panelOpen}
          >
            <Outlet />
          </main>
        </div>
      </div>
    </div>
  )
}

export default AppLayout
