import { useEffect, useState } from 'react'
import { Button, Form, Select, Space, message } from 'antd'
import { api } from '../../api/client'
import type { GenerationPrefs } from '../../types'

export default function GenerationTab() {
  const [form] = Form.useForm<GenerationPrefs>()
  const [saving, setSaving] = useState(false)

  useEffect(() => {
    api.settingsGet().then((s) => form.setFieldsValue(s.generation))
  }, [form])

  const save = async () => {
    const v = await form.validateFields()
    setSaving(true)
    try {
      await api.settingsPut({ generation: v })
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
      <Space>
        <Button type="primary" loading={saving} onClick={save}>保存</Button>
      </Space>
    </Form>
  )
}