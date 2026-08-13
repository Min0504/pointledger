import { Navigate, NavLink, Route, Routes, useLocation } from 'react-router-dom'
import { useAuth } from './auth'
import BatchPage from './pages/BatchPage'
import DashboardPage from './pages/DashboardPage'
import GrantRevokePage from './pages/GrantRevokePage'
import IssuesPage from './pages/IssuesPage'
import LoginPage from './pages/LoginPage'
import SettlementsPage from './pages/SettlementsPage'
import WalletPage from './pages/WalletPage'

const NAV = [
  { to: '/', label: '대시보드' },
  { to: '/wallets', label: '지갑·원장' },
  { to: '/points', label: '지급·회수' },
  { to: '/settlements', label: '정산' },
  { to: '/issues', label: '대사 이슈' },
  { to: '/batch', label: '배치' },
]

export default function App() {
  const { email, logout } = useAuth()
  const location = useLocation()

  if (!email && location.pathname !== '/login') {
    return <Navigate to="/login" replace />
  }

  if (location.pathname === '/login') {
    return (
      <Routes>
        <Route path="/login" element={<LoginPage />} />
      </Routes>
    )
  }

  return (
    <div className="min-h-screen">
      <header className="border-b border-slate-200 bg-white">
        <div className="mx-auto flex max-w-6xl items-center justify-between px-6 py-3">
          <div className="flex items-center gap-8">
            <h1 className="text-lg font-bold tracking-tight">
              Point<span className="text-sky-600">Ledger</span>
              <span className="ml-2 text-xs font-medium text-slate-400">ADMIN</span>
            </h1>
            <nav className="flex gap-1">
              {NAV.map(({ to, label }) => (
                <NavLink
                  key={to}
                  to={to}
                  end={to === '/'}
                  className={({ isActive }) =>
                    `rounded-lg px-3 py-1.5 text-sm font-medium transition ${
                      isActive ? 'bg-slate-900 text-white' : 'text-slate-600 hover:bg-slate-100'
                    }`
                  }
                >
                  {label}
                </NavLink>
              ))}
            </nav>
          </div>
          <div className="flex items-center gap-3 text-sm text-slate-500">
            <span>{email}</span>
            <button onClick={logout} className="text-slate-400 underline-offset-2 hover:underline">
              로그아웃
            </button>
          </div>
        </div>
      </header>
      <main className="mx-auto max-w-6xl px-6 py-6">
        <Routes>
          <Route path="/" element={<DashboardPage />} />
          <Route path="/wallets" element={<WalletPage />} />
          <Route path="/points" element={<GrantRevokePage />} />
          <Route path="/settlements" element={<SettlementsPage />} />
          <Route path="/issues" element={<IssuesPage />} />
          <Route path="/batch" element={<BatchPage />} />
          <Route path="*" element={<Navigate to="/" replace />} />
        </Routes>
      </main>
    </div>
  )
}
