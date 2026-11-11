import { useEffect, useState } from 'react'
import { Button, Form, InputNumber, Space, message } from 'antd'
import { api } from '../../api/client'
import type { ReviewParams } from '../../types'

const DEFAULTS: ReviewParams = { intervalDays: [0, 1, 3, 7, 15, 30], unknownPenalty: 2, unknownDelaySeconds: 60 }

export default function ReviewTab() {
  const [form] = Form.useForm<ReviewParams>()
  const [saving, setSaving] = useState(false)

  useEffect(() => {
    api.settingsGet().then((s) => form.setFieldsValue(s.review))
  }, [form])

  const save = async () => {
    const v = await form.validateFields()
    setSaving(true)
    try {
      await api.settingsPut({ review: v })
      message.success('复习参数已保存,自评立即生效')
    } catch (e) {
      message.error((e as Error).message)
    } finally {
      setSaving(false)
    }
  }

  return (
    <Form form={form} layout="vertical" style={{ maxWidth: 560 }}>
      <Form.Item label="六级掌握度对应的复习间隔(天,非递减)" required>
        <Space wrap>
          {[0, 1, 2, 3, 4, 5].map((i) => (
            <Form.Item
              key={i}
              name={['intervalDays', i]}
              noStyle
              rules={[{ required: true, message: `等级 ${i} 必填` }]}
            >
              <InputNumber min={0} max={365} addonBefore={`L${i}`} style={{ width: 110 }} />
            </Form.Item>
          ))}
        </Space>
      </Form.Item>
      <Form.Item
        name="unknownPenalty"
        label="自评「生疏」扣减掌握度级数"
        rules={[{ required: true }]}
      >
        <InputNumber min={0} max={5} />
      </Form.Item>
      <Form.Item
        name="unknownDelaySeconds"
        label="「生疏」后多少秒重新进入复习队列"
        rules={[{ required: true }]}
      >
        <InputNumber min={0} max={86400} addonAfter="秒" />
      </Form.Item>
      <Space>
        <Button type="primary" loading={saving} onClick={save}>保存</Button>
        <Button onClick={() => form.setFieldsValue(DEFAULTS)}>恢复默认</Button>
      </Space>
    </Form>
  )
}