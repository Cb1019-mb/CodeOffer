import { useState } from 'react'
import { useLocation, useNavigate } from 'react-router-dom'
import { Alert, Button, Card, Form, Input, Tabs, Typography, message } from 'antd'
import { api } from '../api/client'
import { useAuth } from '../auth/authContext'

export default function LoginPage() {
  const [tab, setTab] = useState<'login' | 'register'>('login')
  const [captchaKey, setCaptchaKey] = useState(0)
  const [error, setError] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)
  const { refresh } = useAuth()
  const navigate = useNavigate()
  const location = useLocation()
  const from = (location.state as { from?: string } | null)?.from ?? '/'

  const submit = async (values: { username: string; password: string; captcha: string; confirm?: string }) => {
    setError(null)
    if (tab === 'register' && values.password !== values.confirm) {
      setError('两次输入的密码不一致')
      return
    }
    setBusy(true)
    try {
      if (tab === 'login') {
        await api.login(values.username, values.password, values.captcha)
      } else {
        await api.register(values.username, values.password, values.captcha)
      }
      await refresh()
      message.success(tab === 'login' ? '登录成功' : '注册成功,已自动登录')
      navigate(from, { replace: true })
    } catch (e) {
      setError((e as Error).message)
      setCaptchaKey((k) => k + 1) // 验证码一次性,失败后刷新
    } finally {
      setBusy(false)
    }
  }

  return (
    <div
      style={{
        minHeight: '100vh',
        display: 'flex',
        alignItems: 'center',
        justifyContent: 'center',
        background: '#f0f2f5',
      }}
    >
      <Card style={{ width: 400 }}>
        <Typography.Title level={3} style={{ textAlign: 'center' }}>
          CodeOffer
        </Typography.Title>
        <Tabs
          activeKey={tab}
          onChange={(k) => {
            setTab(k as 'login' | 'register')
            setError(null)
            setCaptchaKey((v) => v + 1)
          }}
          centered
          items={[
            { key: 'login', label: '登录' },
            { key: 'register', label: '注册' },
          ]}
        />
        {error && <Alert type="error" showIcon message={error} style={{ marginBottom: 16 }} />}
        <Form layout="vertical" onFinish={submit} key={tab}>
          <Form.Item
            name="username"
            label="用户名"
            rules={[
              { required: true, message: '请输入用户名' },
              { pattern: /^[A-Za-z0-9_]{3,32}$/, message: '3-32 位字母、数字或下划线' },
            ]}
          >
            <Input autoComplete="username" />
          </Form.Item>
          <Form.Item
            name="password"
            label="密码"
            rules={[
              { required: true, message: '请输入密码' },
              { min: 8, message: '密码至少 8 位' },
            ]}
          >
            <Input.Password autoComplete={tab === 'login' ? 'current-password' : 'new-password'} />
          </Form.Item>
          {tab === 'register' && (
            <Form.Item name="confirm" label="确认密码" rules={[{ required: true, message: '请再次输入密码' }]}>
              <Input.Password autoComplete="new-password" />
            </Form.Item>
          )}
          <Form.Item label="验证码" required>
            <div style={{ display: 'flex', gap: 8 }}>
              <Form.Item name="captcha" noStyle rules={[{ required: true, message: '请输入验证码' }]}>
                <Input placeholder="不区分大小写" style={{ flex: 1 }} />
              </Form.Item>
              <img
                src={`/api/auth/captcha?k=${captchaKey}`}
                alt="验证码,点击刷新"
                title="点击刷新"
                style={{ height: 40, borderRadius: 6, cursor: 'pointer', border: '1px solid #d9d9d9' }}
                onClick={() => setCaptchaKey((k) => k + 1)}
              />
            </div>
          </Form.Item>
          <Button type="primary" htmlType="submit" block loading={busy}>
            {tab === 'login' ? '登录' : '注册'}
          </Button>
        </Form>
      </Card>
    </div>
  )
}