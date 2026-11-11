import { useCallback, useEffect, useState } from 'react'
import { Switch, Table, Tag, message } from 'antd'
import { api } from '../../api/client'
import { useAuth } from '../../auth/authContext'
import type { AdminUserView } from '../../types'

export default function UsersTab() {
  const { user } = useAuth()
  const [users, setUsers] = useState<AdminUserView[]>([])

  const load = useCallback(async () => {
    setUsers(await api.adminUsers())
  }, [])

  useEffect(() => {
    load()
  }, [load])

  const toggle = async (record: AdminUserView, enabled: boolean) => {
    try {
      await api.adminSetStatus(record.id, enabled)
      message.success(enabled ? `已启用 ${record.username}` : `已禁用 ${record.username},其会话已失效`)
      load()
    } catch (e) {
      message.error((e as Error).message)
    }
  }

  return (
    <Table
      rowKey="id"
      dataSource={users}
      pagination={false}
      columns={[
        { title: '用户名', dataIndex: 'username' },
        {
          title: '角色',
          dataIndex: 'role',
          render: (r: string) => (r === 'ADMIN' ? <Tag color="gold">管理员</Tag> : <Tag>用户</Tag>),
        },
        { title: '注册时间', dataIndex: 'createdAt', render: (v: string) => v?.slice(0, 10) },
        {
          title: '启用',
          dataIndex: 'enabled',
          render: (enabled: boolean, record) => (
            <Switch
              checked={enabled}
              disabled={record.id === user?.id}
              onChange={(v) => toggle(record, v)}
            />
          ),
        },
      ]}
    />
  )
}