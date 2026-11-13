import { Tabs, Typography } from 'antd'
import { useSearchParams } from 'react-router-dom'
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
  // 当前 Tab 写入 URL(?tab=),板块切换/刷新后仍停留在上一步的分区
  const [searchParams, setSearchParams] = useSearchParams()
  const items = [
    { key: 'profiles', label: 'AI 模型', children: <ProfilesTab /> },
    { key: 'review', label: '学习/复习', children: <ReviewTab /> },
    { key: 'generation', label: '生成偏好', children: <GenerationTab /> },
    { key: 'data', label: '数据管理', children: <DataTab /> },
    { key: 'account', label: '账号安全', children: <AccountTab /> },
    { key: 'appearance', label: '外观', children: <AppearanceTab /> },
    ...(user?.role === 'ADMIN' ? [{ key: 'users', label: '用户管理', children: <UsersTab /> }] : []),
  ]
  const requested = searchParams.get('tab')
  const activeKey = items.some((i) => i.key === requested) ? requested! : 'profiles'
  return (
    <div style={{ maxWidth: 1080, margin: '32px auto', padding: '0 24px' }}>
      <Typography.Title level={3}>设置</Typography.Title>
      <Tabs
        tabPosition="left"
        items={items}
        destroyOnHidden
        activeKey={activeKey}
        onChange={(k) => setSearchParams({ tab: k }, { replace: true })}
      />
    </div>
  )
}