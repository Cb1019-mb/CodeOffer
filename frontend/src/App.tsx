import { BrowserRouter, Navigate, Route, Routes } from 'react-router-dom'
import 'antd/dist/reset.css'
import { AuthProvider } from './auth/AuthProvider'
import { ThemeProvider } from './theme/ThemeProvider'
import AppLayout from './components/AppLayout'
import RequireAuth from './components/RequireAuth'
import HomePage from './pages/HomePage'
import LoginPage from './pages/LoginPage'
import SettingsPage from './pages/SettingsPage'
import ProjectLayout from './components/project/ProjectLayout'
import OverviewSection from './components/project/OverviewSection'
import CardSection from './components/project/CardSection'
import ResumeStarSection from './components/project/ResumeStarSection'
import InterviewSection from './components/project/InterviewSection'
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
              <Route path="/settings" element={<SettingsPage />} />
              <Route path="/projects/:id" element={<ProjectLayout />}>
                <Route index element={<Navigate to="overview" replace />} />
                <Route path="overview" element={<OverviewSection />} />
                <Route path="cards/:type" element={<CardSection />} />
                <Route path="resume" element={<ResumeStarSection />} />
                <Route path="interview" element={<InterviewSection />} />
              </Route>
            </Route>
          </Routes>
        </BrowserRouter>
      </AuthProvider>
    </ThemeProvider>
  )
}