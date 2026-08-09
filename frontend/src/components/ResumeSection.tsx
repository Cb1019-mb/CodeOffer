import { useState } from 'react'
import { Alert, Button, Card, Collapse, Descriptions, Empty, Input, Space, Tag, Typography, message } from 'antd'
import { ReloadOutlined } from '@ant-design/icons'
import { api } from '../api/client'
import type { ResumeView } from '../types'

interface Props {
  projectId: number
  resume: ResumeView | null
  onChanged: () => void
}

interface Star {
  situation?: string
  task?: string
  action?: string
  result?: string
  summary?: string
}

interface Qa {
  level?: string
  question?: string
  answer?: string
}

const LEVEL_LABEL: Record<string, string> = {
  base: '基础',
  advance: '进阶',
  deep: '深挖',
}

const STAR_FIELDS: { key: keyof Star; label: string }[] = [
  { key: 'situation', label: 'S · 背景' },
  { key: 'task', label: 'T · 任务' },
  { key: 'action', label: 'A · 行动' },
  { key: 'result', label: 'R · 结果' },
  { key: 'summary', label: '一句话亮点' },
]

function parseJson<T>(s: string | null | undefined, fallback: T): T {
  if (!s) return fallback
  try {
    return JSON.parse(s) as T
  } catch {
    return fallback
  }
}

/** 简历条目:STAR 四段编辑 + 面试题分级展示 + 重新生成。 */
export default function ResumeSection({ projectId, resume, onChanged }: Props) {
  const [star, setStar] = useState<Star | null>(() =>
    resume ? parseJson<Star>(resume.starContent, {}) : null,
  )
  const [questions, setQuestions] = useState<Qa[] | null>(() =>
    resume ? parseJson<Qa[]>(resume.interviewQas, []) : null,
  )
  const [regenerating, setRegenerating] = useState(false)
  const [saving, setSaving] = useState(false)
  const [raw, setRaw] = useState<string | null>(null)

  if (!resume) {
    return <Empty description="尚无简历产出,项目生成完成后自动生成" />
  }

  const saveStar = async () => {
    if (!star) return
    setSaving(true)
    try {
      await api.editResumeStar(projectId, JSON.stringify(star, null, 2))
      message.success('简历已保存')
      onChanged()
    } catch (e) {
      message.error((e as Error).message)
    } finally {
      setSaving(false)
    }
  }

  const regenerate = async () => {
    setRegenerating(true)
    try {
      const updated = await api.regenerateResume(projectId)
      setStar(parseJson<Star>(updated.starContent, {}))
      setQuestions(parseJson<Qa[]>(updated.interviewQas, []))
      message.success(updated.edited ? '已更新 AI 草稿(已编辑内容未被覆盖)' : '已重新生成')
      onChanged()
    } catch (e) {
      message.error((e as Error).message)
    } finally {
      setRegenerating(false)
    }
  }

  const grouped = (questions ?? []).reduce<Record<string, Qa[]>>((acc, q) => {
    const level = q.level ?? 'base'
    ;(acc[level] ??= []).push(q)
    return acc
  }, {})

  return (
    <Space direction="vertical" size="middle" style={{ width: '100%' }}>
      <Card
        title="STAR 简历描述"
        extra={
          <Space>
            {resume.edited && <Tag color="orange">已编辑</Tag>}
            <Button icon={<ReloadOutlined />} loading={regenerating} onClick={regenerate}>
              重新生成
            </Button>
          </Space>
        }
      >
        <Descriptions column={1} size="small" bordered>
          {STAR_FIELDS.map(({ key, label }) => (
            <Descriptions.Item key={key} label={label}>
              {star?.[key] ? String(star[key]) : '—'}
            </Descriptions.Item>
          ))}
        </Descriptions>
        <div style={{ marginTop: 12 }}>
          <Space>
            <Button
              onClick={() => setRaw(JSON.stringify(star ?? {}, null, 2))}
            >
              编辑(STAR JSON)
            </Button>
          </Space>
        </div>
        {raw !== null && (
          <div style={{ marginTop: 12 }}>
            <Input.TextArea
              value={raw}
              onChange={(e) => setRaw(e.target.value)}
              rows={12}
            />
            <Space style={{ marginTop: 8 }}>
              <Button type="primary" loading={saving} onClick={saveStar}>
                保存
              </Button>
              <Button onClick={() => setRaw(null)}>取消</Button>
            </Space>
          </div>
        )}
      </Card>

      <Card
        title={`面试题库(${questions?.length ?? 0})`}
        extra={<Typography.Text type="secondary">基础 / 进阶 / 深挖</Typography.Text>}
      >
        {(questions ?? []).length === 0 && <Empty description="暂无面试题" />}
        {Object.entries(grouped).map(([level, list]) => (
          <div key={level} style={{ marginBottom: 12 }}>
            <Tag color={level === 'deep' ? 'red' : level === 'advance' ? 'orange' : 'green'}>
              {LEVEL_LABEL[level] ?? level}({list.length})
            </Tag>
            <Collapse
              size="small"
              items={list.map((q, i) => ({
                key: `${level}-${i}`,
                label: q.question ?? '问题',
                children: <Typography.Paragraph style={{ whiteSpace: 'pre-wrap', margin: 0 }}>{q.answer ?? '—'}</Typography.Paragraph>,
              }))}
            />
          </div>
        ))}
      </Card>

      {!star && !questions && (
        <Alert type="warning" showIcon message="简历数据解析失败" description="可能是旧版本数据,可尝试重新生成。" />
      )}
    </Space>
  )
}
