import { useState } from 'react'
import { Button, Card, Empty, Input, Modal, Popover, Space, Tag, Typography, message } from 'antd'
import { EditOutlined, ReloadOutlined, UndoOutlined } from '@ant-design/icons'
import { api } from '../api/client'
import type { CardView } from '../types'
import MarkdownRenderer from './MarkdownRenderer'

interface Props {
  projectId: number
  cards: CardView[]
  onChanged: () => void
}

/** 运行指南卡:追加个人踩坑记录(写入 card.userInput)。 */
function RunGuideInput({ projectId, card, onChanged }: { projectId: number; card: CardView; onChanged: () => void }) {
  const [input, setInput] = useState('')
  const [submitting, setSubmitting] = useState(false)

  const submit = async () => {
    if (!input.trim()) return
    setSubmitting(true)
    try {
      await api.addUserInput(projectId, card.id, input.trim())
      message.success('踩坑记录已追加')
      setInput('')
      onChanged()
    } catch (e) {
      message.error((e as Error).message)
    } finally {
      setSubmitting(false)
    }
  }

  return (
    <div style={{ marginTop: 8 }}>
      <Space.Compact style={{ width: '100%' }}>
        <Input
          placeholder="记录自己踩过的坑(如:JDK 版本需 17+,否则启动报错)"
          value={input}
          onChange={(e) => setInput(e.target.value)}
          onPressEnter={submit}
        />
        <Button type="primary" loading={submitting} onClick={submit}>
          追加
        </Button>
      </Space.Compact>
    </div>
  )
}

/** 卡片列表:支持编辑、采纳草稿、重新生成(已编辑卡片不被覆盖)。 */
export default function CardList({ projectId, cards, onChanged }: Props) {
  const [editing, setEditing] = useState<CardView | null>(null)
  const [draft, setDraft] = useState('')
  const [busyId, setBusyId] = useState<number | null>(null)
  const [saving, setSaving] = useState(false)

  const openEditor = (card: CardView) => {
    setEditing(card)
    setDraft(card.content ?? card.aiDraft ?? '')
  }

  const saveEdit = async () => {
    if (!editing) return
    setSaving(true)
    try {
      await api.editCard(projectId, editing.id, draft)
      message.success('已保存')
      setEditing(null)
      onChanged()
    } catch (e) {
      message.error((e as Error).message)
    } finally {
      setSaving(false)
    }
  }

  const regenerate = async (card: CardView) => {
    setBusyId(card.id)
    try {
      await api.regenerateCard(projectId, card.id)
      message.success(card.edited ? '已更新 AI 草稿(已编辑内容未被覆盖)' : '已重新生成')
      onChanged()
    } catch (e) {
      message.error((e as Error).message)
    } finally {
      setBusyId(null)
    }
  }

  const adopt = async (card: CardView) => {
    setBusyId(card.id)
    try {
      await api.adoptDraft(projectId, card.id)
      message.success('已采纳 AI 草稿')
      onChanged()
    } catch (e) {
      message.error((e as Error).message)
    } finally {
      setBusyId(null)
    }
  }

  if (cards.length === 0) {
    return <Empty description="暂无卡片" />
  }

  return (
    <Space direction="vertical" size="middle" style={{ width: '100%' }}>
      {cards.map((card) => {
        const hasDraft = card.aiDraft && card.aiDraft !== card.content
        return (
          <Card
            key={card.id}
            size="small"
            title={
              <Space>
                <Tag>{card.typeLabel}</Tag>
                <span>{card.title}</span>
                {card.edited && <Tag color="orange">已编辑</Tag>}
                {card.nextReview && <Tag color="blue">待复习</Tag>}
              </Space>
            }
            extra={
              <Space>
                <Button size="small" icon={<EditOutlined />} onClick={() => openEditor(card)}>
                  编辑
                </Button>
                <Button
                  size="small"
                  icon={<ReloadOutlined />}
                  loading={busyId === card.id}
                  onClick={() => regenerate(card)}
                >
                  重新生成
                </Button>
                {hasDraft && (
                  <Popover
                    content={
                      <div style={{ maxWidth: 420, maxHeight: 300, overflow: 'auto' }}>
                        <MarkdownRenderer content={card.aiDraft ?? ''} />
                      </div>
                    }
                    title="AI 草稿预览"
                    trigger="hover"
                  >
                    <Button size="small" icon={<UndoOutlined />} loading={busyId === card.id} onClick={() => adopt(card)}>
                      采纳草稿
                    </Button>
                  </Popover>
                )}
              </Space>
            }
          >
            {card.type === 'RUN_GUIDE' && card.userInput && (
              <Tag color="geekblue" style={{ marginBottom: 8 }}>
                个人踩坑记录:{(card.userInput ?? '').slice(0, 120)}
                {card.userInput && card.userInput.length > 120 ? '…' : ''}
              </Tag>
            )}
            {card.type === 'FLOW_DIAGRAM' ? (
              <MarkdownRenderer content={card.content ?? card.aiDraft ?? ''} />
            ) : (
              <Typography.Paragraph style={{ whiteSpace: 'pre-wrap', margin: 0 }}>
                {(card.content ?? card.aiDraft ?? '').slice(0, 600)}
                {(card.content?.length ?? 0) > 600 ? '…' : ''}
              </Typography.Paragraph>
            )}
            {card.type === 'RUN_GUIDE' && (
              <RunGuideInput projectId={projectId} card={card} onChanged={onChanged} />
            )}
          </Card>
        )
      })}

      <Modal
        title={editing ? `编辑 · ${editing.typeLabel}` : ''}
        open={editing !== null}
        onCancel={() => setEditing(null)}
        onOk={saveEdit}
        confirmLoading={saving}
        width={720}
      >
        <Input.TextArea
          value={draft}
          onChange={(e) => setDraft(e.target.value)}
          rows={14}
          placeholder="内容(Markdown 或纯文本)"
        />
      </Modal>
    </Space>
  )
}
