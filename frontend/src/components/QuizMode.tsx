import { useCallback, useEffect, useState } from 'react'
import { Button, Card, Empty, Result, Space, Statistic, Tag, Typography, message } from 'antd'
import { CheckOutlined, CloseOutlined, EyeOutlined } from '@ant-design/icons'
import { api } from '../api/client'
import type { CardView } from '../types'
import MarkdownRenderer from './MarkdownRenderer'

interface Props {
  projectId: number
  onDone: () => void
}

/**
 * N1 主动回忆自测:卡片转问题卡(正面=标题问题,背面=内容答案),
 * 翻面核对后自评「掌握/生疏」,更新 mastery 与 next_review。
 */
export default function QuizMode({ projectId, onDone }: Props) {
  const [queue, setQueue] = useState<CardView[] | null>(null)
  const [index, setIndex] = useState(0)
  const [flipped, setFlipped] = useState(false)
  const [submitting, setSubmitting] = useState(false)
  const [knownCount, setKnownCount] = useState(0)
  const [unknownCount, setUnknownCount] = useState(0)
  const [error, setError] = useState<string | null>(null)

  const load = useCallback(async () => {
    try {
      setQueue(await api.reviewQueue(projectId))
    } catch (e) {
      setError((e as Error).message)
    }
  }, [projectId])

  useEffect(() => {
    load()
  }, [load])

  if (error) {
    return <Result status="error" title="加载失败" subTitle={error} extra={<Button onClick={onDone}>返回</Button>} />
  }

  if (queue === null) {
    return <Empty description="加载中…" />
  }

  if (queue.length === 0) {
    return (
      <Result
        icon={<CheckOutlined />}
        title="太棒了,没有待复习卡片!"
        subTitle="所有卡片都已掌握或未到复习时间,先去学习新项目吧。"
        extra={<Button type="primary" onClick={onDone}>完成</Button>}
      />
    )
  }

  const finished = index >= queue.length
  if (finished) {
    return (
      <Result
        status="success"
        title="本轮自测完成"
        subTitle={`共复习 ${queue.length} 张卡片`}
        extra={[
          <Space key="stats">
            <Statistic title="掌握" value={knownCount} valueStyle={{ color: '#52c41a' }} />
            <Statistic title="生疏(已安排复习)" value={unknownCount} valueStyle={{ color: '#fa8c16' }} />
          </Space>,
          <Button key="done" type="primary" onClick={onDone} style={{ marginTop: 16 }}>
            返回项目
          </Button>,
        ]}
      />
    )
  }

  const card = queue[index]

  const selfRate = async (known: boolean) => {
    setSubmitting(true)
    try {
      await api.reviewCard(projectId, card.id, known)
      if (known) setKnownCount((n) => n + 1)
      else setUnknownCount((n) => n + 1)
      setFlipped(false)
      setIndex((i) => i + 1)
    } catch (e) {
      message.error((e as Error).message)
    } finally {
      setSubmitting(false)
    }
  }

  return (
    <Card
      title={
        <Space>
          <Tag>{card.typeLabel}</Tag>
          <span>
            自测 {index + 1} / {queue.length}
          </span>
        </Space>
      }
      style={{ maxWidth: 720, margin: '0 auto' }}
    >
      {!flipped ? (
        <div style={{ textAlign: 'center', padding: '24px 0' }}>
          <Typography.Title level={4}>{card.title || '请回忆这张卡片的核心内容'}</Typography.Title>
          <Typography.Paragraph type="secondary" style={{ marginTop: 8 }}>
            请先凭记忆用自己的话讲清楚这张卡片的内容,再翻面核对答案。
          </Typography.Paragraph>
          <Button type="primary" icon={<EyeOutlined />} onClick={() => setFlipped(true)}>
            显示答案
          </Button>
        </div>
      ) : (
        <div>
          <div style={{ maxHeight: 320, overflow: 'auto', marginBottom: 16 }}>
            <MarkdownRenderer content={card.content ?? card.aiDraft ?? ''} />
          </div>
          <Typography.Text type="secondary" style={{ display: 'block', marginBottom: 12 }}>
            对照答案自评:讲对了点「掌握」,讲不清点「生疏」(生疏的卡片稍后会再次进入复习队列)。
          </Typography.Text>
          <Space>
            <Button type="primary" icon={<CheckOutlined />} loading={submitting} onClick={() => selfRate(true)}>
              掌握
            </Button>
            <Button danger icon={<CloseOutlined />} loading={submitting} onClick={() => selfRate(false)}>
              生疏
            </Button>
          </Space>
        </div>
      )}
    </Card>
  )
}
