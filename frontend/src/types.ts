// 与 Java 后端交互的类型定义(M2 契约)
export interface UserView {
  id: number
  username: string
  role: 'ADMIN' | 'USER'
}

export interface ProjectView {
  id: number
  name: string
  repoUrl: string
  status: string
  cardCount: number
  mastered: number
  due: number
  updatedAt: string
}

export interface TaskView {
  id: number
  type: string
  status: 'RUNNING' | 'DONE' | 'FAILED'
  progress: number
  errorMsg: string | null
}

export interface CardView {
  id: number
  type: string
  typeLabel: string
  title: string
  content: string | null
  aiDraft: string | null
  userInput: string | null
  edited: boolean
  mastery: number
  nextReview: string | null
}

export interface ResumeView {
  starContent: string | null
  interviewQas: string | null
  edited: boolean
}

export interface ProjectDetail {
  id: number
  name: string
  repoUrl: string
  description: string | null
  status: string
  techStack: string | null
  healthReport: string | null
  cards: CardView[]
  resume: ResumeView | null
}

export interface CreateResponse {
  projectId: number
  taskId: number
  status: string
}

export interface LlmProfileView {
  id: number
  name: string
  provider: string
  baseUrl: string
  model: string
  apiKeyMasked: string
  verifySsl: boolean
  active: boolean
}

export interface ProfileInput {
  name: string
  provider: string
  baseUrl: string
  apiKey: string
  model: string
  verifySsl: boolean
}

export interface ReviewParams {
  intervalDays: number[]
  unknownPenalty: number
  unknownDelaySeconds: number
}

export interface GenerationPrefs {
  language: string
  verbosity: string
}

export interface NetworkPrefs {
  proxyUrl: string
}

export interface SettingsView {
  activeProfileId: number | null
  review: ReviewParams
  generation: GenerationPrefs
  network: NetworkPrefs
}

export interface TestResult {
  ok: boolean
  latencyMs: number
  model: string
  error: string | null
}

export interface AdminUserView {
  id: number
  username: string
  role: string
  enabled: boolean
  createdAt: string
}

export const STATUS_LABEL: Record<string, string> = {
  PENDING: '待处理',
  CLONING: '克隆中',
  ANALYZING: '解析中',
  GENERATING: '生成中',
  REVIEW: '待确认',
  DONE: '已入库',
  FAILED: '失败',
}

export const TASK_STATUS_LABEL: Record<string, string> = {
  RUNNING: '进行中',
  DONE: '完成',
  FAILED: '失败',
}
