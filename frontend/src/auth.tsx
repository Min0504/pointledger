import { createContext, useCallback, useContext, useState, type ReactNode } from 'react'
import { api, clearSession, loadEmail, loadToken, saveSession } from './api/client'

interface AuthState {
  email: string | null
  login: (email: string, password: string) => Promise<void>
  logout: () => void
}

const AuthContext = createContext<AuthState | null>(null)

export function AuthProvider({ children }: { children: ReactNode }) {
  const [email, setEmail] = useState<string | null>(() => (loadToken() ? loadEmail() : null))

  const login = useCallback(async (loginEmail: string, password: string) => {
    const res = await api.login(loginEmail, password)
    saveSession(res.accessToken, loginEmail)
    setEmail(loginEmail)
  }, [])

  const logout = useCallback(() => {
    clearSession()
    setEmail(null)
  }, [])

  return <AuthContext.Provider value={{ email, login, logout }}>{children}</AuthContext.Provider>
}

export function useAuth(): AuthState {
  const ctx = useContext(AuthContext)
  if (!ctx) throw new Error('AuthProvider missing')
  return ctx
}
