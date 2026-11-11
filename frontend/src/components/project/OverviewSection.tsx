import { useState } from 'react'
import { useOutletContext } from 'react-router-dom'
import { Button, Card, Descriptions, Space, Typography } from 'antd'
import { api } from '../../api/client'
import MarkdownRenderer from '../MarkdownRenderer'
import type { ProjectContext } from './projectContext'

/** 总览:项目信息 + 体检报告 + 确认入库。 */
export default function OverviewSection() {
  const { projectId, detail, refresh } = useOutletContext<ProjectContext>()
  const [confirming, setConfirming] = useState(false)

  const onConfirm = async () => {
    setConfirming(true)
    try {
      await api.confirm(projectId)
      await refresh()
    } finally {
      setConfirming(false)
    }
  }

  return (
    <Space direction="vertical" size="middle" style={{ width: '100%' }}>
      <Card title="项目信息">
        <Descriptions column={1} size="small">
          <Descriptions.Item label="仓库地址">{detail.repoUrl}</Descriptions.Item>
          <Descriptions.Item label="描述">{detail.description ?? '—'}</Descriptions.Item>
          <Descriptions.Item label="技术栈">{detail.techStack ?? '—'}</Descriptions.Item>
        </Descriptions>
      </Card>

      {detail.healthReport && (
        <Card title="项目体检报告">
          <MarkdownRenderer content={detail.healthReport} />
        </Card>
      )}

      {detail.status === 'REVIEW' && (
        <Card title="待确认">
          <Typography.Paragraph type="secondary">
            AI 已生成知识卡片、简历描述与面试题库(见左侧目录),确认后正式入库。入库后仍可编辑与重新生成。
          </Typography.Paragraph>
          <Button type="primary" loading={confirming} onClick={onConfirm}>
            确认入库
          </Button>
        </Card>
      )}
    </Space>
  )
}