import { useCallback, useEffect, useState } from 'react'
import { Link, useParams } from 'react-router-dom'
import { Alert, Button, Card, Descriptions, Modal, Popconfirm, Progress, Space, Spin, Tag, Typography, message } from 'antd'
import { ArrowLeftOutlined, BulbOutlined, CopyOutlined, DownloadOutlined, ReloadOutlined } from '@ant-design/icons'
import { api } from '../api/client'
import { STATUS_LABEL, TASK_STATUS_LABEL, type ProjectDetail, type TaskView } from '../types'
import CardList from '../components/CardList'
import QuizMode from '../components/QuizMode'
import ResumeSection from '../components/ResumeSection'
import { buildProjectMarkdown, downloadText } from '../utils/export'

export default function ProjectPage() {
  const { id } = useParams()
  const projectId = Number(id)
  const [detail, setDetail] = useState<ProjectDetail | null>(null)
  const [task, setTask] = useState<TaskView | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [confirming, setConfirming] = useState(false)
  const [quizOpen, setQuizOpen] = useState(false)
  const [regenerating, setRegenerating] = useState(false)
  const [refreshKey, setRefreshKey] = useState(0)

  const refresh = useCallback(async () => {
    try {
      const d = await api.getProject(projectId)
      setDetail(d)
      setError(null) // 恢复成功后清除错误横幅
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
    let seq = 0 // 请求序号:丢弃乱序返回的旧轮询结果

    const poll = async () => {
      const mySeq = ++seq
      try {
        const [d, t] = await Promise.all([api.getProject(projectId), api.getTask(projectId)])
        if (stopped || mySeq !== seq) return
        setDetail(d)
        setTask(t)
        setError(null)
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
  }, [projectId, refreshKey])

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

  const exportMarkdown = () => {
    if (!detail) return
    downloadText(`${detail.name || 'project'}.md`, buildProjectMarkdown(detail))
    message.success('已导出 Markdown')
  }

  const regenerate = async () => {
    setRegenerating(true)
    try {
      await api.regenerateProject(projectId)
      message.success('已开始重新生成,进度将实时更新')
      setRefreshKey((k) => k + 1)
    } catch (e) {
      message.error((e as Error).message)
    } finally {
      setRegenerating(false)
    }
  }

  const copyResume = async () => {
    if (!detail?.resume?.starContent) {
      message.warning('暂无简历内容')
      return
    }
    try {
      await navigator.clipboard.writeText(detail.resume.starContent)
      message.success('简历已复制到剪贴板')
    } catch {
      message.error('复制失败,请手动选择复制')
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
          {detail.cards.length > 0 && (
            <>
              <Button icon={<DownloadOutlined />} onClick={exportMarkdown}>
                导出 Markdown
              </Button>
              <Button icon={<CopyOutlined />} onClick={copyResume}>
                复制简历
              </Button>
            </>
          )}
          <Popconfirm
            title="重新生成整个项目?"
            description="将重新克隆解析并替换全部卡片、简历与面试题。"
            okText="重新生成"
            onConfirm={regenerate}
          >
            <Button icon={<ReloadOutlined />} loading={regenerating} type={detail.status === 'FAILED' ? 'primary' : 'default'}>
              重新生成项目
            </Button>
          </Popconfirm>
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
          <Card title="生成失败">
            <Alert type="error" showIcon message="生成失败" description={task.errorMsg ?? '未知错误'} />
          </Card>
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
