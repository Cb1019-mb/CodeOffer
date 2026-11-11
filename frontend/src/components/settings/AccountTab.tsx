import { useState } from 'react'
import { Button, Form, Input, Space, message } from 'antd'
import { api } from '../../api/client'

export default function AccountTab() {
  const [form] = Form.useForm<{ oldPassword: string; newPassword: string; confirm: string }>()
  const [saving, setSaving] = useState(false)

  const save = async () => {
    const v = await form.validateFields()
    if (v.newPassword !== v.confirm) {
      message.error('两次输入的新密码不一致')
      return
    }
    setSaving(true)
    try {
      await api.changePassword(v.oldPassword, v.newPassword)
      message.success('密码已修改,其它会话已失效')
      form.resetFields()
    } catch (e) {
      message.error((e as Error).message)
    } finally {
      setSaving(false)
    }
  }

  return (
    <Form form={form} layout="vertical" style={{ maxWidth: 420 }}>
      <Form.Item name="oldPassword" label="旧密码" rules={[{ required: true, message: '请输入旧密码' }]}>
        <Input.Password autoComplete="current-password" />
      </Form.Item>
      <Form.Item name="newPassword" label="新密码" rules={[{ required: true }, { min: 8, message: '至少 8 位' }]}>
        <Input.Password autoComplete="new-password" />
      </Form.Item>
      <Form.Item name="confirm" label="确认新密码" rules={[{ required: true, message: '请再次输入新密码' }]}>
        <Input.Password autoComplete="new-password" />
      </Form.Item>
      <Space>
        <Button type="primary" loading={saving} onClick={save}>修改密码</Button>
      </Space>
    </Form>
  )
}