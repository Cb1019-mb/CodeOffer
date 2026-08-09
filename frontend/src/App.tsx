import { useEffect, useState } from 'react'
import { Card, Col, Row, Typography, Tag, Space, Alert } from 'antd'
import { ConfigProvider } from 'antd'
import zhCN from 'antd/locale/zh_CN'
import 'antd/dist/reset.css'
import './index.css'

interface Health {
  status: string
  service?: string
  version?: string
}

function useHealth(url: string) {
  const [state, setState] = useState<{ health?: Health; error?: string }>({})
  useEffect(() => {
    let alive = true
    fetch(url)
      .then((r) => (r.ok ? r.json() : Promise.reject(new Error(`HTTP ${r.status}`))))
      .then((h) => alive && setState({ health: h }))
      .catch((e) => alive && setState({ error: String(e) }))
    return () => {
      alive = false
    }
  }, [url])
  return state
}

function HealthCard({ title, url }: { title: string; url: string }) {
  const { health, error } = useHealth(url)
  return (
    <Card title={title} size="small">
      {health ? (
        <Space>
          <Tag color={health.status === 'ok' ? 'green' : 'red'}>{health.status}</Tag>
          <span>{health.service ?? 'unknown'}</span>
          {health.version && <Typography.Text type="secondary">{health.version}</Typography.Text>}
        </Space>
      ) : error ? (
        <Tag color="red">连接失败:{error}</Tag>
      ) : (
        <Tag>检测中...</Tag>
      )}
    </Card>
  )
}

export default function App() {
  return (
    <ConfigProvider locale={zhCN}>
      <div style={{ maxWidth: 900, margin: '48px auto', padding: '0 24px' }}>
        <Typography.Title level={2}>CodeOffer</Typography.Title>
        <Typography.Paragraph type="secondary">
          智能项目学习与简历生成引擎 · M0 工程骨架(验证双层服务联通)
        </Typography.Paragraph>
        <Alert
          type="info"
          showIcon
          message="使用说明"
          description="Java 后端经由 Vite 代理(相对路径 /api/health);Python AI 服务直连 8000 端口(已启用 CORS)。"
          style={{ marginBottom: 16 }}
        />
        <Row gutter={16}>
          <Col span={12}>
            <HealthCard title="Java 后端 · 8080" url="/api/health" />
          </Col>
          <Col span={12}>
            <HealthCard title="Python AI 服务 · 8000" url="http://127.0.0.1:8000/api/health" />
          </Col>
        </Row>
      </div>
    </ConfigProvider>
  )
}
