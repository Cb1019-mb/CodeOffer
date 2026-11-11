import { useEffect, useState } from 'react'
import { useOutletContext } from 'react-router-dom'
import { Button, Card, Collapse, Empty, Space, Tag, Typography, message } from 'antd'
import { ReloadOutlined } from '@ant-design/icons'
import { api } from '../../api/client'
import type { ProjectContext } from './projectContext'
import { LEVEL_LABEL, parseJson, type Qa } from './resumeShared'

/** 模拟面试题视图:基础/进阶/深挖分级 + 重生成。 */
export default function InterviewSection() {
  const { projectId, detail, refresh } = useOutletContext<ProjectContext>()
  const resume = detail.resume
  const [questions, setQuestions] = useState<Qa[] | null>(() =>
    resume ? parseJson<Qa[]>(resume.interviewQas, []) : null,
  )
  const [regenerating, setRegenerating] = useState(false)

  useEffect(() => {
    setQuestions(resume ? parseJson<Qa[]>(resume.interviewQas, []) : null)
  }, [resume])

  if (!resume) {
    return <Empty description="尚无简历产出,项目生成完成后自动生成" />
  }

  const regenerate = async () => {
    setRegenerating(true)
    try {
      const updated = await api.regenerateResume(projectId)
      setQuestions(parseJson<Qa[]>(updated.interviewQas, []))
      message.success(updated.edited ? '已更新 AI 草稿(已编辑内容未被覆盖)' : '已重新生成')
      await refresh()
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
    <Card
      title={`面试题库(${questions?.length ?? 0})`}
      extra={
        <Space>
          <Typography.Text type="secondary">基础 / 进阶 / 深挖</Typography.Text>
          <Button icon={<ReloadOutlined />} loading={regenerating} onClick={regenerate}>
            重新生成
          </Button>
        </Space>
      }
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
              children: (
                <Typography.Paragraph style={{ whiteSpace: 'pre-wrap', margin: 0 }}>
                  {q.answer ?? '—'}
                </Typography.Paragraph>
              ),
            }))}
          />
        </div>
      ))}
    </Card>
  )
}