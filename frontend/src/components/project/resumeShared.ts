export interface Star {
  situation?: string
  task?: string
  action?: string
  result?: string
  summary?: string
}

export interface Qa {
  level?: string
  question?: string
  answer?: string
}

export const LEVEL_LABEL: Record<string, string> = {
  base: '基础',
  advance: '进阶',
  deep: '深挖',
}

export const STAR_FIELDS: { key: keyof Star; label: string }[] = [
  { key: 'situation', label: 'S · 背景' },
  { key: 'task', label: 'T · 任务' },
  { key: 'action', label: 'A · 行动' },
  { key: 'result', label: 'R · 结果' },
  { key: 'summary', label: '一句话亮点' },
]

export function parseJson<T>(s: string | null | undefined, fallback: T): T {
  if (!s) return fallback
  try {
    return JSON.parse(s) as T
  } catch {
    return fallback
  }
}