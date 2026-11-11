import type { ProjectDetail, TaskView } from '../../types'

export interface ProjectContext {
  projectId: number
  detail: ProjectDetail
  task: TaskView | null
  refresh: () => Promise<ProjectDetail | null>
}

export const CARD_TYPES = [
  { type: 'ARCHITECTURE', label: '架构设计' },
  { type: 'DIFFICULTY', label: '技术难点' },
  { type: 'HIGHLIGHT', label: '项目亮点' },
  { type: 'TAKEAWAY', label: '学习收获' },
  { type: 'RUN_GUIDE', label: '运行指南' },
  { type: 'FLOW_DIAGRAM', label: '核心流程图' },
]