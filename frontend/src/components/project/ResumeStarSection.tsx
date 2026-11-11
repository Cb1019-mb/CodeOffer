import { useEffect, useState } from 'react'
import { useOutletContext } from 'react-router-dom'
import { Button, Card, Descriptions, Empty, Input, Space, Tag, message } from 'antd'
import { ReloadOutlined } from '@ant-design/icons'
import { api } from '../../api/client'
import type { ProjectContext } from './projectContext'
import { STAR_FIELDS, parseJson, type Star } from './resumeShared'

/** STAR 简历视图:四段展示 + JSON 编辑 + 重生成。 */
export default function ResumeStarSection() {
  const { projectId, detail, refresh } = useOutletContext<ProjectContext>()
  const resume = detail.resume
  const [star, setStar] = useState<Star | null>(() =>
    resume ? parseJson<Star>(resume.starContent, {}) : null,
  )
  const [regenerating, setRegenerating] = useState(false)
  const [saving, setSaving] = useState(false)
  const [raw, setRaw] = useState<string | null>(null)

  useEffect(() => {
    setStar(resume ? parseJson<Star>(resume.starContent, {}) : null)
  }, [resume])

  if (!resume) {
    return <Empty description="尚无简历产出,项目生成完成后自动生成" />
  }

  const saveStar = async (value?: Star) => {
    const payload = value ?? star
    if (!payload) return
    setSaving(true)
    try {
      await api.editResumeStar(projectId, JSON.stringify(payload, null, 2))
      message.success('简历已保存')
      setRaw(null)
      await refresh()
    } catch (e) {
      message.error((e as Error).message)
    } finally {
      setSaving(false)
    }
  }

  const regenerate = async () => {
    setRegenerating(true)
    try {
      const updated = await api.regenerateResume(projectId)
      setStar(parseJson<Star>(updated.starContent, {}))
      message.success(updated.edited ? '已更新 AI 草稿(已编辑内容未被覆盖)' : '已重新生成')
      await refresh()
    } catch (e) {
      message.error((e as Error).message)
    } finally {
      setRegenerating(false)
    }
  }

  return (
    <Card
      title="STAR 简历描述"
      extra={
        <Space>
          {resume.edited && <Tag color="orange">已编辑</Tag>}
          <Button icon={<ReloadOutlined />} loading={regenerating} onClick={regenerate}>
            重新生成
          </Button>
        </Space>
      }
    >
      <Descriptions column={1} size="small" bordered>
        {STAR_FIELDS.map(({ key, label }) => (
          <Descriptions.Item key={key} label={label}>
            {star?.[key] ? String(star[key]) : '—'}
          </Descriptions.Item>
        ))}
      </Descriptions>
      <div style={{ marginTop: 12 }}>
        <Button onClick={() => setRaw(JSON.stringify(star ?? {}, null, 2))}>
          编辑(STAR JSON)
        </Button>
      </div>
      {raw !== null && (
        <div style={{ marginTop: 12 }}>
          <Input.TextArea value={raw} onChange={(e) => setRaw(e.target.value)} rows={12} />
          <Space style={{ marginTop: 8 }}>
            <Button
              type="primary"
              loading={saving}
              onClick={() => saveStar(parseJson<Star>(raw, star ?? {}))}
            >
              保存
            </Button>
            <Button onClick={() => setRaw(null)}>取消</Button>
          </Space>
        </div>
      )}
    </Card>
  )
}