import { useCallback, useEffect, useState } from 'react'
import { Link, Outlet, useLocation, useNavigate, useParams } from 'react-router-dom'
import {
  Alert, Button, Card, Layout, Menu, Modal, Popconfirm, Progress, Space, Spin, Tag, Typography, message,
} from 'antd'
import {
  ArrowLeftOutlined, BulbOutlined, CheckCircleTwoTone, ClockCircleTwoTone,
  CopyOutlined, DownloadOutlined, ReloadOutlined,
} from '@ant-design/icons'
import { api } from '../../api/client'
import { STATUS_LABEL, TASK_STATUS_LABEL, type ProjectDetail, type TaskView } from '../../types'
import QuizMode from '../QuizMode'
import { buildProjectMarkdown, downloadText } from '../../utils/export'
import { CARD_TYPES, type ProjectContext } from './projectContext'

/** 学习页布局:轮询中枢 + 头部操作 + 全局横幅 + 左侧目录 + 子路由内容区。 */
export default function ProjectLayout() {
  const { id } = useParams()
  const projectId = Number(id)
  const navigate = useNavigate()
  const location = useLocation()
  const [detail, setDetail] = useState<ProjectDetail | null>(null)
  const [task, setTask] = useState<TaskView | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [quizOpen, setQuizOpen] = useState(false)
  const [regenerating, setRegenerating] = useState(false)
  const [refreshKey, setRefreshKey] = useState(0)

  const refresh = useCallback(async () => {
    try {
      const d = await api.getProject(projectId)
      setDetail(d)
      setError(null)
      return d
    } catch (e) {
      setError((e as Error).message)
      return null
    }
  }, [projectId])

  useEffect(() => {
    // 切换项目时清空旧数据,避免短暂展示上一个项目的内容
    setDetail(null)
    setTask(null)
    setError(null)
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
    interval = setInterval(poll, 3000)
    return () => {
      stopped = true
      if (interval) clearInterval(interval)
    }
  }, [projectId, refreshKey])

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
        <Button type="link" onClick={() => navigate('/')}>
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
  const sub = location.pathname.replace(`/projects/${projectId}`, '').replace(/^\//, '') || 'overview'

  const badgeFor = (type: string) => {
    const card = detail.cards.find((c) => c.type === type)
    if (!card) return null
    if (card.mastery >= 3) return <CheckCircleTwoTone twoToneColor="#52c41a" />
    const due = card.mastery === 0 || (card.nextReview !== null && new Date(card.nextReview) <= new Date())
    if (due) return <ClockCircleTwoTone twoToneColor="#fa8c16" />
    return null
  }

  const menuItems = [
    { key: 'overview', label: '总览' },
    ...CARD_TYPES.map((t) => ({
      key: `cards/${t.type}`,
      label: (
        <Space>
          {t.label}
          {badgeFor(t.type)}
        </Space>
      ),
    })),
    { key: 'resume', label: 'STAR 简历' },
    { key: 'interview', label: '模拟面试题' },
  ]

  return (
    <div style={{ maxWidth: 1200, margin: '24px auto', padding: '0 24px' }}>
      <Space direction="vertical" size="middle" style={{ width: '100%' }}>
        <Space wrap>
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
          <Button icon={<BulbOutlined />} disabled={detail.cards.length === 0} onClick={() => setQuizOpen(true)}>
            开始自测
          </Button>
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

        <Layout style={{ background: 'transparent' }}>
          <Layout.Sider breakpoint="lg" collapsedWidth={0} width={200} style={{ background: 'transparent' }}>
            <Menu
              mode="inline"
              selectedKeys={[sub]}
              items={menuItems}
              onClick={({ key }) => navigate(`/projects/${projectId}/${key}`)}
              style={{ borderInlineEnd: 'none' }}
            />
          </Layout.Sider>
          <Layout.Content style={{ padding: '0 0 0 24px', minWidth: 0 }}>
            <Outlet context={{ projectId, detail, task, refresh } satisfies ProjectContext} />
          </Layout.Content>
        </Layout>
      </Space>

      <Modal
        title="主动回忆自测"
        open={quizOpen}
        footer={null}
        width={800}
        onCancel={() => setQuizOpen(false)}
        destroyOnHidden
      >
        <QuizMode projectId={projectId} onDone={() => setQuizOpen(false)} />
      </Modal>
    </div>
  )
}