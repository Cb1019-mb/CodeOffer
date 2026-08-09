// API 客户端:相对路径经 Vite proxy 转发到 Java 后端(8080)
import type { CreateResponse, ProjectDetail, ProjectView, TaskView } from '../types'

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
  return resp.json() as Promise<T>
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
}
