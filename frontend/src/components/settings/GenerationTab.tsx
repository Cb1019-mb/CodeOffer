import { useEffect, useState } from 'react'
import { Button, Divider, Form, Input, Select, Space, message } from 'antd'
import { api } from '../../api/client'

interface FormValues {
  language: string
  verbosity: string
  proxyUrl: string
}

export default function GenerationTab() {
  const [form] = Form.useForm<FormValues>()
  const [saving, setSaving] = useState(false)

  useEffect(() => {
    api.settingsGet().then((s) =>
      form.setFieldsValue({ ...s.generation, proxyUrl: s.network?.proxyUrl ?? '' }),
    )
  }, [form])

  const save = async () => {
    const v = await form.validateFields()
    setSaving(true)
    try {
      await api.settingsPut({
        generation: { language: v.language, verbosity: v.verbosity },
        network: { proxyUrl: (v.proxyUrl ?? '').trim() },
      })
      message.success('生成偏好已保存,下一次生成生效')
    } catch (e) {
      message.error((e as Error).message)
    } finally {
      setSaving(false)
    }
  }

  return (
    <Form form={form} layout="vertical" style={{ maxWidth: 420 }}>
      <Form.Item name="language" label="产物语言" rules={[{ required: true }]}>
        <Select
          options={[
            { value: 'zh', label: '简体中文' },
            { value: 'en', label: 'English' },
          ]}
        />
      </Form.Item>
      <Form.Item name="verbosity" label="篇幅倾向" rules={[{ required: true }]}>
        <Select
          options={[
            { value: 'concise', label: '精简(约标准一半)' },
            { value: 'standard', label: '标准' },
            { value: 'detailed', label: '详尽(约标准 1.5 倍)' },
          ]}
        />
      </Form.Item>
      <Divider style={{ margin: '8px 0 16px' }} />
      <Form.Item
        name="proxyUrl"
        label="仓库克隆代理"
        extra="克隆 GitHub 等境外仓库时使用,留空沿用本机 git/环境代理;支持 http / https / socks5。不影响 AI 模型调用。"
        rules={[{ pattern: /^$|^(https?|socks5h?):\/\/\S+$/, message: '格式如 http://127.0.0.1:7890' }]}
      >
        <Input placeholder="http://127.0.0.1:7890" allowClear />
      </Form.Item>
      <Space>
        <Button type="primary" loading={saving} onClick={save}>保存</Button>
      </Space>
    </Form>
  )
}