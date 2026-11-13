import { createContext, useContext } from 'react'

export type ThemeMode = 'light' | 'dark' | 'system'

export const ThemeContext = createContext<{ mode: ThemeMode; dark: boolean; setMode: (m: ThemeMode) => void }>({
  mode: 'system',
  dark: false,
  setMode: () => {},
})

export function useTheme() {
  return useContext(ThemeContext)
}