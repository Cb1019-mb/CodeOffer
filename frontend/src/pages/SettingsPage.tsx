import { Tabs, Typography } from 'antd'
import { useAuth } from '../auth/authContext'
import ProfilesTab from '../components/settings/ProfilesTab'
import ReviewTab from '../components/settings/ReviewTab'
import GenerationTab from '../components/settings/GenerationTab'
import DataTab from '../components/settings/DataTab'
import AccountTab from '../components/settings/AccountTab'
import AppearanceTab from '../components/settings/AppearanceTab'
import UsersTab from '../components/settings/UsersTab'

export default function SettingsPage() {
  const { user } = useAuth()
  const items = [
    { key: 'profiles', label: 'AI 模型', children: <ProfilesTab /> },
    { key: 'review', label: '学习/复习', children: <ReviewTab /> },
    { key: 'generation', label: '生成偏好', children: <GenerationTab /> },
    { key: 'data', label: '数据管理', children: <DataTab /> },
    { key: 'account', label: '账号安全', children: <AccountTab /> },
    { key: 'appearance', label: '外观', children: <AppearanceTab /> },
    ...(user?.role === 'ADMIN' ? [{ key: 'users', label: '用户管理', children: <UsersTab /> }] : []),
  ]
  return (
    <div style={{ maxWidth: 1080, margin: '32px auto', padding: '0 24px' }}>
      <Typography.Title level={3}>设置</Typography.Title>
      <Tabs tabPosition="left" items={items} destroyOnHidden />
    </div>
  )
}