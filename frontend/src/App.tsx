import { BrowserRouter, Route, Routes } from 'react-router-dom'
import 'antd/dist/reset.css'
import { AuthProvider } from './auth/AuthProvider'
import { ThemeProvider } from './theme/ThemeProvider'
import AppLayout from './components/AppLayout'
import RequireAuth from './components/RequireAuth'
import HomePage from './pages/HomePage'
import ProjectPage from './pages/ProjectPage'
import LoginPage from './pages/LoginPage'
import SettingsPage from './pages/SettingsPage'
import './index.css'

export default function App() {
  return (
    <ThemeProvider>
      <AuthProvider>
        <BrowserRouter>
          <Routes>
            <Route path="/login" element={<LoginPage />} />
            <Route
              element={
                <RequireAuth>
                  <AppLayout />
                </RequireAuth>
              }
            >
              <Route path="/" element={<HomePage />} />
              <Route path="/projects/:id" element={<ProjectPage />} />
              <Route path="/settings" element={<SettingsPage />} />
            </Route>
          </Routes>
        </BrowserRouter>
      </AuthProvider>
    </ThemeProvider>
  )
}