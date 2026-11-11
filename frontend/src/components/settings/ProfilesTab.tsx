import { useCallback, useEffect, useState } from 'react'
import { Alert, Button, Card, Form, Input, Modal, Popconfirm, Select, Space, Switch, Tag, message } from 'antd'
import { DeleteOutlined, EditOutlined, PlusOutlined, ThunderboltOutlined } from '@ant-design/icons'
import { api } from '../../api/client'
import type { LlmProfileView, ProfileInput, TestResult } from '../../types'

const PRESETS: Record<string, { baseUrl: string; model: string }> = {
  deepseek: { baseUrl: 'https://api.deepseek.com/v1', model: 'deepseek-chat' },
  qwen: { baseUrl: 'https://dashscope.aliyuncs.com/compatible-mode/v1', model: 'qwen-plus' },
  zhipu: { baseUrl: 'https://open.bigmodel.cn/api/paas/v4', model: 'glm-4-flash' },
  vllm: { baseUrl: 'http://localhost:8000/v1', model: '' },
  custom: { baseUrl: '', model: '' },
}

export default function ProfilesTab() {
  const [profiles, setProfiles] = useState<LlmProfileView[]>([])
  const [editing, setEditing] = useState<LlmProfileView | null>(null)
  const [modalOpen, setModalOpen] = useState(false)
  const [testing, setTesting] = useState<number | null>(null)
  const [results, setResults] = useState<Record<number, TestResult>>({})
  const [form] = Form.useForm<ProfileInput>()

  const load = useCallback(async () => {
    setProfiles(await api.profilesList())
  }, [])

  useEffect(() => {
    load()
  }, [load])

  const openCreate = () => {
    setEditing(null)
    form.resetFields()
    form.setFieldsValue({ provider: 'deepseek', verifySsl: true, ...PRESETS.deepseek })
    setModalOpen(true)
  }

  const openEdit = (p: LlmProfileView) => {
    setEditing(p)
    form.setFieldsValue({
      name: p.name, provider: p.provider, baseUrl: p.baseUrl, model: p.model,
      apiKey: '', verifySsl: p.verifySsl,
    })
    setModalOpen(true)
  }

  const onProviderChange = (provider: string) => {
    const preset = PRESETS[provider]
    if (preset) form.setFieldsValue({ baseUrl: preset.baseUrl, model: preset.model })
  }

  const save = async () => {
    const values = await form.validateFields()
    try {
      if (editing) {
        await api.profileUpdate(editing.id, values)
        message.success('档案已更新')
      } else {
        await api.profileCreate(values)
        message.success('档案已创建')
      }
      setModalOpen(false)
      load()
    } catch (e) {
      message.error((e as Error).message)
    }
  }

  const activate = async (id: number) => {
    try {
      await api.profileActivate(id)
      message.success('已切换激活档案,下一次生成生效')
      load()
    } catch (e) {
      message.error((e as Error).message)
    }
  }

  const test = async (id: number) => {
    setTesting(id)
    try {
      const r = await api.profileTest(id)
      setResults((prev) => ({ ...prev, [id]: r }))
    } catch (e) {
      setResults((prev) => ({ ...prev, [id]: { ok: false, latencyMs: 0, model: '', error: (e as Error).message } }))
    } finally {
      setTesting(null)
    }
  }

  const remove = async (id: number) => {
    try {
      await api.profileDelete(id)
      message.success('档案已删除')
      load()
    } catch (e) {
      message.error((e as Error).message)
    }
  }

  const importEnv = async () => {
    try {
      await api.profileImportEnv()
      message.success('已从服务器 .env 导入档案')
      load()
    } catch (e) {
      message.error((e as Error).message)
    }
  }

  return (
    <Space direction="vertical" size="middle" style={{ width: '100%' }}>
      {profiles.length === 0 && (
        <Alert
          type="warning"
          showIcon
          message="尚未配置 AI 提供商"
          description="创建并激活一个档案后才能生成知识产物。每个用户自带 Key(BYOK),互不共享。"
        />
      )}
      <Space>
        <Button type="primary" icon={<PlusOutlined />} onClick={openCreate}>新建档案</Button>
        <Button onClick={importEnv}>从 .env 导入</Button>
      </Space>
      {profiles.map((p) => (
        <Card
          key={p.id}
          size="small"
          title={
            <Space>
              <span>{p.name}</span>
              <Tag>{p.provider}</Tag>
              {p.active && <Tag color="green">激活中</Tag>}
            </Space>
          }
          extra={
            <Space>
              <Button size="small" icon={<ThunderboltOutlined />} loading={testing === p.id} onClick={() => test(p.id)}>
                测试连接
              </Button>
              {!p.active && <Button size="small" type="primary" onClick={() => activate(p.id)}>激活</Button>}
              <Button size="small" icon={<EditOutlined />} onClick={() => openEdit(p)}>编辑</Button>
              <Popconfirm title="删除该档案?" okText="删除" okButtonProps={{ danger: true }} onConfirm={() => remove(p.id)}>
                <Button size="small" danger icon={<DeleteOutlined />} />
              </Popconfirm>
            </Space>
          }
        >
          <Space direction="vertical" size={4} style={{ width: '100%' }}>
            <span>端点:{p.baseUrl}</span>
            <span>模型:{p.model} · Key:{p.apiKeyMasked} · SSL 校验:{p.verifySsl ? '开' : '关'}</span>
            {results[p.id] && (
              results[p.id].ok ? (
                <Alert type="success" showIcon message={`连接成功:${results[p.id].model},延迟 ${results[p.id].latencyMs}ms`} />
              ) : (
                <Alert type="error" showIcon message={`连接失败:${results[p.id].error}`} />
              )
            )}
          </Space>
        </Card>
      ))}
      <Modal
        title={editing ? `编辑档案 · ${editing.name}` : '新建 LLM 档案'}
        open={modalOpen}
        onOk={save}
        onCancel={() => setModalOpen(false)}
        okText="保存"
        width={560}
      >
        <Form form={form} layout="vertical">
          <Form.Item name="name" label="档案名" rules={[{ required: true, message: '请输入档案名' }, { max: 50 }]}>
            <Input placeholder="如:DeepSeek 云端 / 本地 vLLM" />
          </Form.Item>
          <Form.Item name="provider" label="提供商预设" rules={[{ required: true }]}>
            <Select
              onChange={onProviderChange}
              options={[
                { value: 'deepseek', label: 'DeepSeek' },
                { value: 'qwen', label: '通义千问' },
                { value: 'zhipu', label: '智谱' },
                { value: 'vllm', label: '本地 vLLM' },
                { value: 'custom', label: '自定义' },
              ]}
            />
          </Form.Item>
          <Form.Item name="baseUrl" label="端点(base_url)" rules={[{ required: true, message: '请输入端点' }, { pattern: /^https?:\/\/.+/, message: '需 http(s):// 开头' }]}>
            <Input placeholder="https://api.deepseek.com/v1" />
          </Form.Item>
          <Form.Item
            name="apiKey"
            label={editing ? 'API Key(留空保留原值)' : 'API Key'}
            rules={editing ? [] : [{ required: true, message: '请输入 API Key' }]}
          >
            <Input.Password placeholder="sk-..." autoComplete="new-password" />
          </Form.Item>
          <Form.Item name="model" label="模型名" rules={[{ required: true, message: '请输入模型名' }]}>
            <Input placeholder="deepseek-chat" />
          </Form.Item>
          <Form.Item name="verifySsl" label="SSL 证书校验" valuePropName="checked">
            <Switch />
          </Form.Item>
        </Form>
      </Modal>
    </Space>
  )
}