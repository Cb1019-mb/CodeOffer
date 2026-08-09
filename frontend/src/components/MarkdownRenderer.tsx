import { useEffect, useRef } from 'react'
import ReactMarkdown from 'react-markdown'
import mermaid from 'mermaid'

mermaid.initialize({ startOnLoad: false, theme: 'default', securityLevel: 'loose' })

let mermaidSeq = 0

function escapeHtml(s: string): string {
  return s.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;')
}

/** Mermaid 代码块渲染;渲染失败降级为代码展示(设计文档 §7)。 */
function MermaidBlock({ code }: { code: string }) {
  const ref = useRef<HTMLDivElement>(null)
  useEffect(() => {
    let cancelled = false
    const el = ref.current
    if (!el) return
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
  }, [code])
  return <div ref={ref} />
}

/** Markdown 渲染:支持 ```mermaid 代码块。 */
export default function MarkdownRenderer({ content }: { content: string }) {
  return (
    <div className="markdown-body">
      <ReactMarkdown
        components={{
          code({ className, children }) {
            const lang = /language-(\w+)/.exec(className ?? '')?.[1]
            if (lang === 'mermaid') {
              return <MermaidBlock code={String(children).trim()} />
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
