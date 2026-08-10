// 导出工具:项目内容组装为 Markdown 并下载 / 复制
import type { ProjectDetail } from '../types'

/** 把项目全部成果(卡片/简历/面试题)组装为 Markdown 文档。 */
export function buildProjectMarkdown(detail: ProjectDetail): string {
  const lines: string[] = []
  lines.push(`# ${detail.name}`, '', `> ${detail.repoUrl}`, '')

  lines.push('## 知识卡片')
  if (detail.cards.length === 0) {
    lines.push('(暂无)')
  }
  for (const c of detail.cards) {
    lines.push(`### [${c.typeLabel}] ${c.title}`, '', c.content ?? c.aiDraft ?? '', '')
    if (c.userInput) {
      lines.push(`> 个人补充:${c.userInput}`, '')
    }
  }

  if (detail.resume?.starContent) {
    lines.push('## STAR 简历', '', '```json', detail.resume.starContent, '```', '')
  }
  if (detail.resume?.interviewQas) {
    lines.push('## 面试题库', '', '```json', detail.resume.interviewQas, '```', '')
  }
  return lines.join('\n')
}

/** 下载文本为文件(UTF-8)。 */
export function downloadText(filename: string, text: string): void {
  const blob = new Blob([text], { type: 'text/markdown;charset=utf-8' })
  const url = URL.createObjectURL(blob)
  const a = document.createElement('a')
  a.href = url
  a.download = filename
  document.body.appendChild(a)
  a.click()
  a.remove()
  // 延迟释放,避免个别浏览器(Safari/大文件)在下载开始前就撤销导致失败
  setTimeout(() => URL.revokeObjectURL(url), 1000)
}
