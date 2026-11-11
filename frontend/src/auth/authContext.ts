import { createContext, useContext } from 'react'
import type { UserView } from '../types'

export interface AuthState {
  user: UserView | null
  loading: boolean
  refresh: () => Promise<void>
  logout: () => Promise<void>
}

export const AuthContext = createContext<AuthState>({
  user: null,
  loading: true,
  refresh: async () => {},
  logout: async () => {},
})

export function useAuth() {
  return useContext(AuthContext)
}