import { useEffect, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import {
  Alert,
  Button,
  Card,
  Col,
  Form,
  Input,
  List,
  Popconfirm,
  Row,
  Space,
  Tag,
  Typography,
  message,
} from 'antd'
import { PlusOutlined } from '@ant-design/icons'
import { api } from '../api/client'
import { STATUS_LABEL, type ProjectView } from '../types'
import { forgetProjectPath } from '../utils/navMemory'

const BATCH_LIMIT = 20

interface BatchResult {
  url: string
  ok: boolean
  error?: string
}

/** 解析批量录入:每行一个地址,去空白与重复,保持顺序。 */
function parseBatch(text: string): string[] {
  const seen = new Set<string>()
  const urls: string[] = []
  for (const line of text.split('\n')) {
    const u = line.trim()
    if (u && !seen.has(u)) {
      seen.add(u)
      urls.push(u)
    }
  }
  return urls
}

export default function HomePage() {
  const navigate = useNavigate()
  const [projects, setProjects] = useState<ProjectView[]>([])
  const [submitting, setSubmitting] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [batch, setBatch] = useState(false)
  const [batchText, setBatchText] = useState('')
  const [batchProgress, setBatchProgress] = useState<{ done: number; total: number } | null>(null)
  const [batchResults, setBatchResults] = useState<BatchResult[] | null>(null)

  const load = async () => {
    try {
      setProjects(await api.listProjects())
    } catch (e) {
      setError((e as Error).message)
    }
  }

  useEffect(() => {
    load()
  }, [])

  const onFinish = async (values: { repoUrl: string }) => {
    setSubmitting(true)
    setError(null)
    try {
      const created = await api.createProject(values.repoUrl.trim())
      navigate(`/projects/${created.projectId}`)
    } catch (e) {
      setError((e as Error).message)
    } finally {
      setSubmitting(false)
    }
  }

  const batchUrls = parseBatch(batchText)

  const onBatchSubmit = async () => {
    if (batchUrls.length === 0) {
      message.warning('请至少输入一个仓库地址')
      return
    }
    if (batchUrls.length > BATCH_LIMIT) {
      message.warning(`单次最多 ${BATCH_LIMIT} 个,请分批录入`)
      return
    }
    setSubmitting(true)
    setError(null)
    setBatchResults(null)
    const results: BatchResult[] = []
    for (let i = 0; i < batchUrls.length; i++) {
      setBatchProgress({ done: i, total: batchUrls.length })
      try {
        await api.createProject(batchUrls[i])
        results.push({ url: batchUrls[i], ok: true })
      } catch (e) {
        results.push({ url: batchUrls[i], ok: false, error: (e as Error).message })
      }
    }
    setBatchProgress(null)
    setBatchResults(results)
    setSubmitting(false)
    const okCount = results.filter((r) => r.ok).length
    if (okCount > 0) {
      message.success(`已录入 ${okCount} 个项目,生成任务排队执行`)
      load()
    } else {
      message.error('全部录入失败,请检查地址或网络')
    }
  }

  const toggleBatch = () => {
    setBatch((b) => !b)
    setBatchResults(null)
    setError(null)
  }

  const del = async (id: number) => {
    try {
      await api.deleteProject(id)
      forgetProjectPath(id)
      message.success('项目已删除')
      load()
    } catch (e) {
      message.error((e as Error).message)
    }
  }

  return (
    <div style={{ maxWidth: 960, margin: '32px auto', padding: '0 24px' }}>
      <Typography.Paragraph type="secondary">
        智能项目学习与简历生成引擎 · 粘贴 GitHub 仓库地址,AI 帮你解析、内化并产出简历素材
      </Typography.Paragraph>

      {error && (
        <Alert type="error" showIcon message="操作失败" description={error} style={{ marginBottom: 16 }} />
      )}

      <Card
        title="录入新项目"
        style={{ marginBottom: 24 }}
        extra={
          <Button type="link" size="small" onClick={toggleBatch} disabled={submitting}>
            {batch ? '单个录入' : '批量录入'}
          </Button>
        }
      >
        {!batch ? (
          <Form layout="inline" onFinish={onFinish}>
            <Form.Item
              name="repoUrl"
              rules={[{ required: true, whitespace: true, message: '请输入仓库地址' }]}
              style={{ flex: 1 }}
            >
              <Input placeholder="https://github.com/owner/repo" size="large" />
            </Form.Item>
            <Form.Item>
              <Button type="primary" htmlType="submit" icon={<PlusOutlined />} loading={submitting} size="large">
                开始学习
              </Button>
            </Form.Item>
          </Form>
        ) : (
          <Space direction="vertical" size="middle" style={{ width: '100%' }}>
            <Input.TextArea
              value={batchText}
              onChange={(e) => setBatchText(e.target.value)}
              autoSize={{ minRows: 4, maxRows: 10 }}
              placeholder={'每行一个仓库地址,自动去重,例如:\nhttps://github.com/owner/repo\nhttps://gitee.com/owner/repo'}
              disabled={submitting}
            />
            <Space wrap>
              <Button type="primary" icon={<PlusOutlined />} loading={submitting} onClick={onBatchSubmit}>
                {batchProgress ? `录入中 ${batchProgress.done}/${batchProgress.total}` : `批量开始(${batchUrls.length})`}
              </Button>
              <Typography.Text type="secondary">单次最多 {BATCH_LIMIT} 个;生成任务按顺序排队执行</Typography.Text>
            </Space>
            {batchResults && (
              <List
                size="small"
                dataSource={batchResults}
                style={{ maxHeight: 240, overflow: 'auto' }}
                renderItem={(r) => (
                  <List.Item>
                    <Space wrap>
                      <Tag color={r.ok ? 'green' : 'red'}>{r.ok ? '已录入' : '失败'}</Tag>
                      <Typography.Text style={{ wordBreak: 'break-all' }}>{r.url}</Typography.Text>
                      {r.error && <Typography.Text type="danger">{r.error}</Typography.Text>}
                    </Space>
                  </List.Item>
                )}
              />
            )}
          </Space>
        )}
      </Card>

      <Card
        title="我的项目"
        extra={
          <Button size="small" onClick={load}>
            刷新
          </Button>
        }
      >
        <List
          dataSource={projects}
          locale={{ emptyText: '还没有项目,粘贴一个仓库地址开始吧' }}
          renderItem={(p) => (
            <List.Item
              actions={[
                <Button key="open" type="link" onClick={() => navigate(`/projects/${p.id}`)}>
                  查看
                </Button>,
                <Popconfirm
                  key="del"
                  title="删除该项目?"
                  description="将同时删除其卡片、简历与面试题,不可恢复。"
                  okText="删除"
                  okButtonProps={{ danger: true }}
                  onConfirm={() => del(p.id)}
                >
                  <Button type="link" danger>
                    删除
                  </Button>
                </Popconfirm>,
              ]}
            >
              <List.Item.Meta
                title={
                  <Space>
                    <span>{p.name}</span>
                    <Tag color={p.status === 'FAILED' ? 'red' : p.status === 'DONE' ? 'green' : 'blue'}>
                      {STATUS_LABEL[p.status] ?? p.status}
                    </Tag>
                  </Space>
                }
                description={p.repoUrl}
              />
              <MasteryBadge cardCount={p.cardCount} mastered={p.mastered} due={p.due} />
            </List.Item>
          )}
        />
      </Card>
    </div>
  )
}

/** 掌握度徽标(N7 浅做):卡片数 / 已掌握 / 待复习 */
export function MasteryBadge({ cardCount, mastered, due }: { cardCount: number; mastered: number; due: number }) {
  return (
    <Row gutter={8} justify="end" style={{ minWidth: 220 }}>
      <Col>
        <Tag>卡片 {cardCount}</Tag>
      </Col>
      <Col>
        <Tag color="green">已掌握 {mastered}</Tag>
      </Col>
      <Col>
        <Tag color={due > 0 ? 'orange' : 'default'}>待复习 {due}</Tag>
      </Col>
    </Row>
  )
}
