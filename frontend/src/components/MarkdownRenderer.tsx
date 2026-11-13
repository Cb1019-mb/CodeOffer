import { useEffect, useRef } from 'react'
import ReactMarkdown from 'react-markdown'
import mermaid from 'mermaid'
import { useTheme } from '../theme/themeContext'

mermaid.initialize({ startOnLoad: false, theme: 'default' })

let mermaidSeq = 0

function escapeHtml(s: string): string {
  return s.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;')
}

/** Mermaid 代码块渲染;主题跟随明暗切换,渲染失败降级为代码展示(设计文档 §7)。 */
function MermaidBlock({ code, dark }: { code: string; dark: boolean }) {
  const ref = useRef<HTMLDivElement>(null)
  useEffect(() => {
    let cancelled = false
    const el = ref.current
    if (!el) return
    mermaid.initialize({ startOnLoad: false, theme: dark ? 'dark' : 'default' })
    mermaidSeq += 1
    const id = `mmd-${mermaidSeq}`
    mermaid
      .render(id, code)
      .then(({ svg }) => {
        if (!cancelled) el.innerHTML = svg
      })
      .catch(() => {
        if (!cancelled) el.innerHTML = `<pre style="white-space:pre-wrap">${escapeHtml(code)}</pre>`
      })
    return () => {
      cancelled = true
    }
  }, [code, dark])
  return <div ref={ref} />
}

/** Markdown 渲染:支持 ```mermaid 代码块。 */
export default function MarkdownRenderer({ content }: { content: string }) {
  const { dark } = useTheme()
  return (
    <div className="markdown-body">
      <ReactMarkdown
        components={{
          code({ className, children }) {
            const lang = /language-(\w+)/.exec(className ?? '')?.[1]
            if (lang === 'mermaid') {
              return <MermaidBlock code={String(children).trim()} dark={dark} />
            }
            return <code className={className}>{children}</code>
          },
        }}
      >
        {content}
      </ReactMarkdown>
    </div>
  )
}