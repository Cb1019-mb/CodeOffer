// API 客户端:相对路径经 Vite proxy 转发到 Java 后端(8080)
import type {
  CardView,
  CreateResponse,
  ProjectDetail,
  ProjectView,
  ResumeView,
  TaskView,
} from '../types'

const DEFAULT_TIMEOUT = 30_000
/** 同步执行的 AI 重新生成类请求耗时较长(LLM 单次 60s × 重试 ≈ 185s),单独放宽超时。 */
const LONG_TIMEOUT = 220_000

interface RequestOptions extends RequestInit {
  timeout?: number
}

async function request<T>(path: string, options: RequestOptions = {}): Promise<T> {
  const timeout = options.timeout ?? DEFAULT_TIMEOUT
  const controller = new AbortController()
  const timer = setTimeout(() => controller.abort(), timeout)
  try {
    const resp = await fetch(path, {
      headers: { 'Content-Type': 'application/json' },
      signal: controller.signal,
      ...options,
    })
    if (!resp.ok) {
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
}
