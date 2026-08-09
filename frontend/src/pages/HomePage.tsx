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

export default function HomePage() {
  const navigate = useNavigate()
  const [projects, setProjects] = useState<ProjectView[]>([])
  const [submitting, setSubmitting] = useState(false)
  const [error, setError] = useState<string | null>(null)

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

  const del = async (id: number) => {
    try {
      await api.deleteProject(id)
      message.success('项目已删除')
      load()
    } catch (e) {
      message.error((e as Error).message)
    }
  }

  return (
    <div style={{ maxWidth: 960, margin: '32px auto', padding: '0 24px' }}>
      <Typography.Title level={2}>CodeOffer</Typography.Title>
      <Typography.Paragraph type="secondary">
        智能项目学习与简历生成引擎 · 粘贴 GitHub 仓库地址,AI 帮你解析、内化并产出简历素材
      </Typography.Paragraph>

      {error && (
        <Alert type="error" showIcon message="操作失败" description={error} style={{ marginBottom: 16 }} />
      )}

      <Card title="录入新项目" style={{ marginBottom: 24 }}>
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
