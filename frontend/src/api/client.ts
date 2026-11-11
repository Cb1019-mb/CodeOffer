// API 客户端:相对路径经 Vite proxy 转发到 Java 后端(8080)
import type {
  AdminUserView,
  CardView,
  CreateResponse,
  GenerationPrefs,
  LlmProfileView,
  ProfileInput,
  ProjectDetail,
  ProjectView,
  ResumeView,
  ReviewParams,
  SettingsView,
  TaskView,
  TestResult,
  UserView,
} from '../types'

const DEFAULT_TIMEOUT = 30_000
/** 同步执行的 AI 重新生成类请求耗时较长(LLM 单次 60s × 重试 ≈ 185s),单独放宽超时。 */
const LONG_TIMEOUT = 220_000

interface RequestOptions extends RequestInit {
  timeout?: number
}

/** CSRF:从 XSRF-TOKEN cookie 取值,写请求带 X-XSRF-TOKEN 头(Spring Security spa 模式)。 */
function xsrfHeader(): Record<string, string> {
  const match = document.cookie.match(/(?:^|;\s*)XSRF-TOKEN=([^;]*)/)
  return match ? { 'X-XSRF-TOKEN': decodeURIComponent(match[1]) } : {}
}

async function request<T>(path: string, options: RequestOptions = {}): Promise<T> {
  const timeout = options.timeout ?? DEFAULT_TIMEOUT
  const controller = new AbortController()
  const timer = setTimeout(() => controller.abort(), timeout)
  try {
    const resp = await fetch(path, {
      headers: { 'Content-Type': 'application/json', ...xsrfHeader() },
      signal: controller.signal,
      ...options,
    })
    if (!resp.ok) {
      // 会话失效统一跳登录页(认证端点自身除外,避免登录页死循环)
      if (resp.status === 401 && !path.startsWith('/api/auth/')) {
        window.location.assign('/login')
      }
      let detail: unknown = `HTTP ${resp.status}`
      try {
        const body = await resp.json()
        detail = body.detail ?? detail
      } catch {
        // 非 JSON 错误体,保留默认
      }
      throw new Error(typeof detail === 'string' ? detail : JSON.stringify(detail))
    }
    // 空响应体(如 DELETE 成功)不解析 JSON,避免 "Unexpected end of JSON input"
    const text = await resp.text()
    if (!text) {
      return undefined as T
    }
    return JSON.parse(text) as T
  } catch (e) {
    if (e instanceof DOMException && e.name === 'AbortError') {
      throw new Error(`请求超时(${Math.round(timeout / 1000)}s),请稍后重试`)
    }
    throw e
  } finally {
    clearTimeout(timer)
  }
}

export const api = {
  // 认证
  me: () => request<UserView>('/api/auth/me'),
  login: (username: string, password: string, captcha: string) =>
    request<UserView>('/api/auth/login', {
      method: 'POST',
      body: JSON.stringify({ username, password, captcha }),
    }),
  register: (username: string, password: string, captcha: string) =>
    request<UserView>('/api/auth/register', {
      method: 'POST',
      body: JSON.stringify({ username, password, captcha }),
    }),
  logout: () => request<void>('/api/auth/logout', { method: 'POST' }),

  createProject: (repoUrl: string) =>
    request<CreateResponse>('/api/projects', {
      method: 'POST',
      body: JSON.stringify({ repoUrl }),
    }),
  listProjects: () => request<ProjectView[]>('/api/projects'),
  getProject: (id: number) => request<ProjectDetail>(`/api/projects/${id}`),
  getTask: (id: number) => request<TaskView>(`/api/projects/${id}/task`),
  confirm: (id: number) =>
    request<ProjectDetail>(`/api/projects/${id}/confirm`, { method: 'POST' }),
  deleteProject: (id: number) => request<void>(`/api/projects/${id}`, { method: 'DELETE' }),
  regenerateProject: (id: number) =>
    request<CreateResponse>(`/api/projects/${id}/regenerate`, { method: 'POST' }),

  // M4:卡片编辑与重新生成
  editCard: (id: number, cardId: number, content: string) =>
    request<CardView>(`/api/projects/${id}/cards/${cardId}/content`, {
      method: 'PUT',
      body: JSON.stringify({ content }),
    }),
  adoptDraft: (id: number, cardId: number) =>
    request<CardView>(`/api/projects/${id}/cards/${cardId}/adopt`, { method: 'POST' }),
  regenerateCard: (id: number, cardId: number) =>
    request<CardView>(`/api/projects/${id}/cards/${cardId}/regenerate`, {
      method: 'POST',
      timeout: LONG_TIMEOUT,
    }),
  addUserInput: (id: number, cardId: number, input: string) =>
    request<CardView>(`/api/projects/${id}/cards/${cardId}/user-input`, {
      method: 'POST',
      body: JSON.stringify({ input }),
    }),

  // M4:简历编辑与重新生成
  editResumeStar: (id: number, starContent: string) =>
    request<ResumeView>(`/api/projects/${id}/resume/star`, {
      method: 'PUT',
      body: JSON.stringify({ starContent }),
    }),
  regenerateResume: (id: number) =>
    request<ResumeView>(`/api/projects/${id}/resume/regenerate`, {
      method: 'POST',
      timeout: LONG_TIMEOUT,
    }),

  // M5:自测与掌握度
  reviewCard: (id: number, cardId: number, known: boolean) =>
    request<CardView>(`/api/projects/${id}/cards/${cardId}/review`, {
      method: 'POST',
      body: JSON.stringify({ known }),
    }),
  reviewQueue: (id: number) => request<CardView[]>(`/api/projects/${id}/review-queue`),

  // 设置:聚合与档案
  settingsGet: () => request<SettingsView>('/api/settings'),
  settingsPut: (body: { activeProfileId?: number; review?: ReviewParams; generation?: GenerationPrefs }) =>
    request<void>('/api/settings', { method: 'PUT', body: JSON.stringify(body) }),
  profilesList: () => request<LlmProfileView[]>('/api/settings/llm-profiles'),
  profileCreate: (body: ProfileInput) =>
    request<LlmProfileView>('/api/settings/llm-profiles', { method: 'POST', body: JSON.stringify(body) }),
  profileUpdate: (id: number, body: ProfileInput) =>
    request<LlmProfileView>(`/api/settings/llm-profiles/${id}`, { method: 'PUT', body: JSON.stringify(body) }),
  profileDelete: (id: number) => request<void>(`/api/settings/llm-profiles/${id}`, { method: 'DELETE' }),
  profileActivate: (id: number) =>
    request<void>(`/api/settings/llm-profiles/${id}/activate`, { method: 'POST' }),
  profileTest: (id: number) =>
    request<TestResult>(`/api/settings/llm-profiles/${id}/test`, { method: 'POST', timeout: 40_000 }),
  profileImportEnv: () =>
    request<LlmProfileView>('/api/settings/llm-profiles/import-env', { method: 'POST' }),

  // 设置:数据管理 / 账号 / 管理员
  storageInfo: () => request<{ sizeBytes: number; repoCount: number }>('/api/settings/storage-info'),
  clearRepoCache: () => request<{ ok: boolean; removed: number }>('/api/settings/clear-repo-cache', { method: 'POST' }),
  changePassword: (oldPassword: string, newPassword: string) =>
    request<void>('/api/auth/password', { method: 'PUT', body: JSON.stringify({ oldPassword, newPassword }) }),
  adminUsers: () => request<AdminUserView[]>('/api/admin/users'),
  adminSetStatus: (id: number, enabled: boolean) =>
    request<void>(`/api/admin/users/${id}/status`, { method: 'PUT', body: JSON.stringify({ enabled }) }),
  backupDownload: async () => {
    const resp = await fetch('/api/settings/backup', { headers: { ...xsrfHeader() } })
    if (!resp.ok) throw new Error(`HTTP ${resp.status}`)
    const blob = await resp.blob()
    const url = URL.createObjectURL(blob)
    const a = document.createElement('a')
    a.href = url
    a.download = `codeoffer-backup-${new Date().toISOString().slice(0, 10)}.json`
    a.click()
    URL.revokeObjectURL(url)
  },
}
