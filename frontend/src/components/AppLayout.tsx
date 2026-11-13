import { useEffect, useState } from 'react'
import { Layout, Menu, Dropdown, Button, Tag, Space } from 'antd'
import { Link, Outlet, useLocation, useNavigate } from 'react-router-dom'
import { LogoutOutlined, UserOutlined } from '@ant-design/icons'
import { useAuth } from '../auth/authContext'
import { getProjectPath, getSettingsPath, rememberPath } from '../utils/navMemory'

export default function AppLayout() {
  const { user, logout } = useAuth()
  const location = useLocation()
  const navigate = useNavigate()
  const [projectPath, setProjectPath] = useState(getProjectPath)
  const [settingsPath, setSettingsPath] = useState(getSettingsPath)

  // 记录各板块最近页面:菜单切换时回到上一步(项目子路由 / 设置当前 Tab)
  useEffect(() => {
    const full = location.pathname + location.search
    rememberPath(full)
    if (full.startsWith('/settings')) {
      setSettingsPath(full)
    } else if (full === '/' || full.startsWith('/projects/')) {
      setProjectPath(full)
    }
  }, [location])

  const onLogout = async () => {
    await logout()
    navigate('/login')
  }

  return (
    <Layout style={{ minHeight: '100vh' }}>
      <Layout.Header style={{ display: 'flex', alignItems: 'center', gap: 24 }}>
        <Link to="/" style={{ color: '#fff', fontSize: 18, fontWeight: 600, whiteSpace: 'nowrap' }}>
          CodeOffer
        </Link>
        <Menu
          theme="dark"
          mode="horizontal"
          selectedKeys={[location.pathname.startsWith('/settings') ? 'settings' : 'home']}
          items={[
            { key: 'home', label: <Link to={projectPath}>项目</Link> },
            { key: 'settings', label: <Link to={settingsPath}>设置</Link> },
          ]}
          style={{ flex: 1, minWidth: 0 }}
        />
        <Dropdown
          menu={{
            items: [
              { key: 'logout', icon: <LogoutOutlined />, label: '退出登录', onClick: onLogout },
            ],
          }}
        >
          <Button type="text" style={{ color: '#fff' }}>
            <Space>
              <UserOutlined />
              {user?.username}
              {user?.role === 'ADMIN' && <Tag color="gold">管理员</Tag>}
            </Space>
          </Button>
        </Dropdown>
      </Layout.Header>
      <Layout.Content>
        <Outlet />
      </Layout.Content>
    </Layout>
  )
}