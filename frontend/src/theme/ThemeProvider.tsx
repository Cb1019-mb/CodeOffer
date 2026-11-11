import { useEffect, useState, type ReactNode } from 'react'
import { ConfigProvider, theme as antdTheme } from 'antd'
import zhCN from 'antd/locale/zh_CN'
import { ThemeContext, type ThemeMode } from './themeContext'

function resolveDark(mode: ThemeMode): boolean {
  if (mode === 'dark') return true
  if (mode === 'light') return false
  return window.matchMedia('(prefers-color-scheme: dark)').matches
}

export function ThemeProvider({ children }: { children: ReactNode }) {
  const [mode, setModeState] = useState<ThemeMode>(
    () => (localStorage.getItem('codeoffer-theme') as ThemeMode) || 'system',
  )
  const [dark, setDark] = useState(() => resolveDark(mode))

  const setMode = (m: ThemeMode) => {
    localStorage.setItem('codeoffer-theme', m)
    setModeState(m)
    setDark(resolveDark(m))
  }

  useEffect(() => {
    const mq = window.matchMedia('(prefers-color-scheme: dark)')
    const onChange = () => {
      if (mode === 'system') setDark(mq.matches)
    }
    mq.addEventListener('change', onChange)
    return () => mq.removeEventListener('change', onChange)
  }, [mode])

  return (
    <ThemeContext value={{ mode, setMode }}>
      <ConfigProvider
        locale={zhCN}
        theme={{ algorithm: dark ? antdTheme.darkAlgorithm : antdTheme.defaultAlgorithm }}
      >
        {children}
      </ConfigProvider>
    </ThemeContext>
  )
}