import { createContext, useContext } from 'react'

export type ThemeMode = 'light' | 'dark' | 'system'

export const ThemeContext = createContext<{ mode: ThemeMode; setMode: (m: ThemeMode) => void }>({
  mode: 'system',
  setMode: () => {},
})

export function useTheme() {
  return useContext(ThemeContext)
}