import { useEffect, useState } from 'react'
import { Link, useParams } from 'react-router-dom'
import { Alert, Button, Card, Descriptions, Progress, Space, Spin, Tag, Typography } from 'antd'
import { ArrowLeftOutlined } from '@ant-design/icons'
import { api } from '../api/client'
import { STATUS_LABEL, TASK_STATUS_LABEL, type ProjectDetail, type TaskView } from '../types'

export default function ProjectPage() {
  const { id } = useParams()
  const projectId = Number(id)
  const [detail, setDetail] = useState<ProjectDetail | null>(null)
  const [task, setTask] = useState<TaskView | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [confirming, setConfirming] = useState(false)

  useEffect(() => {
    if (!Number.isFinite(projectId)) {
      setError('无效的项目 ID')
      return
    }
    let stopped = false
    let interval: ReturnType<typeof setInterval> | undefined

    const refresh = async () => {
      try {
        const [d, t] = await Promise.all([api.getProject(projectId), api.getTask(projectId)])
        if (stopped) return
        setDetail(d)
        setTask(t)
        if (t && (t.status === 'DONE' || t.status === 'FAILED') && interval) {
          clearInterval(interval)
        }
      } catch (e) {
        if (!stopped) setError((e as Error).message)
      }
    }

    refresh()
    interval = setInterval(refresh, 3000) // 轮询任务进度
    return () => {
      stopped = true
      if (interval) clearInterval(interval)
    }
  }, [projectId])

  const onConfirm = async () => {
    setConfirming(true)
    try {
      setDetail(await api.confirm(projectId))
    } catch (e) {
      setError((e as Error).message)
    } finally {
      setConfirming(false)
    }
  }

  if (error && !detail) {
    return (
      <div style={{ maxWidth: 960, margin: '32px auto', padding: '0 24px' }}>
        <Alert type="error" showIcon message="加载失败" description={error} />
        <Button type="link" onClick={() => window.history.back()}>
          返回
        </Button>
      </div>
    )
  }

  if (!detail) {
    return (
      <div style={{ textAlign: 'center', marginTop: 80 }}>
        <Spin size="large" />
      </div>
    )
  }

  const running = task && task.status === 'RUNNING'

  return (
    <div style={{ maxWidth: 960, margin: '32px auto', padding: '0 24px' }}>
      <Space direction="vertical" size="large" style={{ width: '100%' }}>
        <Space>
          <Link to="/">
            <Button icon={<ArrowLeftOutlined />}>返回</Button>
          </Link>
          <Typography.Title level={3} style={{ margin: 0 }}>
            {detail.name}
          </Typography.Title>
          <Tag color={detail.status === 'FAILED' ? 'red' : detail.status === 'DONE' ? 'green' : 'blue'}>
            {STATUS_LABEL[detail.status] ?? detail.status}
          </Tag>
        </Space>

        {error && <Alert type="error" showIcon message="出错" description={error} />}

        {running && (
          <Card title="AI 处理进度">
            <Progress
              percent={task?.progress ?? 0}
              status={task?.status === 'FAILED' ? 'exception' : 'active'}
            />
            <Typography.Text type="secondary">
              当前阶段:{STATUS_LABEL[detail.status] ?? detail.status}(任务
              {TASK_STATUS_LABEL[task?.status ?? 'RUNNING']})
            </Typography.Text>
          </Card>
        )}

        {task?.status === 'FAILED' && (
          <Alert type="error" showIcon message="生成失败" description={task.errorMsg ?? '未知错误'} />
        )}

        <Card title="项目信息">
          <Descriptions column={1} size="small">
            <Descriptions.Item label="仓库地址">{detail.repoUrl}</Descriptions.Item>
            <Descriptions.Item label="描述">{detail.description ?? '—'}</Descriptions.Item>
            <Descriptions.Item label="技术栈">{detail.techStack ?? '—'}</Descriptions.Item>
          </Descriptions>
        </Card>

        {detail.status === 'REVIEW' && (
          <Card title="待确认">
            <Typography.Paragraph type="secondary">
              AI 已生成知识卡片、简历描述与面试题库(见下方),确认后正式入库。
            </Typography.Paragraph>
            <Button type="primary" loading={confirming} onClick={onConfirm}>
              确认入库
            </Button>
          </Card>
        )}

        <Card title={`知识卡片(${detail.cards.length})`}>
          {detail.cards.length === 0 ? (
            <Typography.Text type="secondary">暂无卡片</Typography.Text>
          ) : (
            <Space direction="vertical" style={{ width: '100%' }} size="small">
              {detail.cards.map((c) => (
                <Card key={c.id} size="small" title={<Space><Tag>{c.typeLabel}</Tag>{c.title}</Space>}>
                  <Typography.Paragraph style={{ whiteSpace: 'pre-wrap', margin: 0 }}>
                    {(c.content ?? c.aiDraft ?? '').slice(0, 500)}
                  </Typography.Paragraph>
                  {c.edited && <Tag color="orange">已编辑</Tag>}
                </Card>
              ))}
            </Space>
          )}
        </Card>
      </Space>
    </div>
  )
}
