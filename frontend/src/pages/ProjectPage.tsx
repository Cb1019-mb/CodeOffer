import { useCallback, useEffect, useState } from 'react'
import { Link, useParams } from 'react-router-dom'
import { Alert, Button, Card, Descriptions, Modal, Progress, Space, Spin, Tag, Typography } from 'antd'
import { ArrowLeftOutlined, BulbOutlined } from '@ant-design/icons'
import { api } from '../api/client'
import { STATUS_LABEL, TASK_STATUS_LABEL, type ProjectDetail, type TaskView } from '../types'
import CardList from '../components/CardList'
import QuizMode from '../components/QuizMode'
import ResumeSection from '../components/ResumeSection'

export default function ProjectPage() {
  const { id } = useParams()
  const projectId = Number(id)
  const [detail, setDetail] = useState<ProjectDetail | null>(null)
  const [task, setTask] = useState<TaskView | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [confirming, setConfirming] = useState(false)
  const [quizOpen, setQuizOpen] = useState(false)

  const refresh = useCallback(async () => {
    try {
      const d = await api.getProject(projectId)
      setDetail(d)
      return d
    } catch (e) {
      setError((e as Error).message)
      return null
    }
  }, [projectId])

  useEffect(() => {
    if (!Number.isFinite(projectId)) {
      setError('无效的项目 ID')
      return
    }
    let stopped = false
    let interval: ReturnType<typeof setInterval> | undefined

    const poll = async () => {
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

    poll()
    interval = setInterval(poll, 3000) // 轮询任务进度
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
            <Progress percent={task?.progress ?? 0} status="active" />
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
              AI 已生成知识卡片、简历描述与面试题库(见下方),确认后正式入库。入库后仍可编辑与重新生成。
            </Typography.Paragraph>
            <Button type="primary" loading={confirming} onClick={onConfirm}>
              确认入库
            </Button>
          </Card>
        )}

        <Card
          title={`知识卡片(${detail.cards.length})`}
          extra={
            <Button icon={<BulbOutlined />} disabled={detail.cards.length === 0} onClick={() => setQuizOpen(true)}>
              开始自测
            </Button>
          }
        >
          <CardList projectId={projectId} cards={detail.cards} onChanged={refresh} />
        </Card>

        <Modal
          title="主动回忆自测"
          open={quizOpen}
          footer={null}
          width={800}
          onCancel={() => setQuizOpen(false)}
          destroyOnClose
        >
          <QuizMode projectId={projectId} onDone={() => setQuizOpen(false)} />
        </Modal>

        <Card title="简历与面试题">
          <ResumeSection projectId={projectId} resume={detail.resume} onChanged={refresh} />
        </Card>
      </Space>
    </div>
  )
}
