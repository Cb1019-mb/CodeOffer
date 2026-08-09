// API 客户端:相对路径经 Vite proxy 转发到 Java 后端(8080)
import type {
  CardView,
  CreateResponse,
  ProjectDetail,
  ProjectView,
  ResumeView,
  TaskView,
} from '../types'

async function request<T>(path: string, options?: RequestInit): Promise<T> {
  const resp = await fetch(path, {
    headers: { 'Content-Type': 'application/json' },
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
    request<CardView>(`/api/projects/${id}/cards/${cardId}/regenerate`, { method: 'POST' }),
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
    request<ResumeView>(`/api/projects/${id}/resume/regenerate`, { method: 'POST' }),

  // M5:自测与掌握度
  reviewCard: (id: number, cardId: number, known: boolean) =>
    request<CardView>(`/api/projects/${id}/cards/${cardId}/review`, {
      method: 'POST',
      body: JSON.stringify({ known }),
    }),
  reviewQueue: (id: number) => request<CardView[]>(`/api/projects/${id}/review-queue`),
}
